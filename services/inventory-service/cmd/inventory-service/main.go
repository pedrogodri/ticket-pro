// Command inventory-service: event catalog, stock reservations and ticket issuing of Ticket Pro.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ticketpro/inventory-service/internal/app"
	"github.com/ticketpro/inventory-service/internal/modules/catalog"
	"github.com/ticketpro/inventory-service/internal/modules/reservations"
	"github.com/ticketpro/inventory-service/internal/modules/tickets"
	"github.com/ticketpro/inventory-service/internal/platform/envelope"
	"github.com/ticketpro/inventory-service/internal/platform/logging"
	"github.com/ticketpro/inventory-service/internal/platform/messaging"
	"github.com/ticketpro/inventory-service/internal/platform/outbox"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}

	cfg, err := app.LoadConfig(os.Getenv)
	if err != nil {
		logging.New(os.Stderr, "inventory-service", slog.LevelInfo).Error("invalid_config", "error", err.Error())
		os.Exit(1)
	}
	logger := logging.New(os.Stdout, cfg.ServiceName, cfg.LogLevel)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := run(ctx, cfg, logger); err != nil {
		logger.Error("service_failed", "error", err.Error())
		os.Exit(1)
	}
}

func run(ctx context.Context, cfg app.Config, logger *slog.Logger) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	// Pools connect lazily: the service starts even if the database is still coming up (readiness says so).
	appPool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("DATABASE_URL: %w", err)
	}
	defer appPool.Close()
	workerPool, err := pgxpool.New(ctx, cfg.DatabaseWorkerURL)
	if err != nil {
		return fmt.Errorf("DATABASE_WORKER_URL: %w", err)
	}
	defer workerPool.Close()

	awsCfg, err := messaging.NewAWSConfig(ctx, cfg.HTTPClientTimeout)
	if err != nil {
		return err
	}
	relay := outbox.NewRelay(outbox.RelayConfig{
		Pool:         workerPool,
		Publisher:    messaging.NewSNSPublisher(sns.NewFromConfig(awsCfg)),
		BatchSize:    cfg.Outbox.BatchSize,
		PollInterval: cfg.Outbox.PollInterval,
		MaxBackoff:   cfg.Outbox.MaxBackoff,
		ClaimLease:   cfg.Outbox.ClaimLease,
		Logger:       logger,
	})

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := relay.Run(ctx); err != nil && !errors.Is(err, context.Canceled) {
			logger.Error("outbox_relay_stopped", "error", err.Error())
		}
	}()

	validator, err := envelope.NewValidator(cfg.ContractsDir)
	if err != nil {
		return fmt.Errorf("CONTRACTS_DIR: %w", err)
	}
	consumer := messaging.NewConsumer(messaging.ConsumerConfig{
		SQS: sqs.NewFromConfig(awsCfg), QueueURL: cfg.QueueURL, Name: cfg.ServiceName, Pool: workerPool,
		Validator: validator, Concurrency: cfg.Consumer.Concurrency, WaitTimeS: cfg.Consumer.WaitTimeS,
		RequestTimeout: cfg.HTTPClientTimeout, Logger: logger,
		Handlers: map[string]messaging.Handler{
			"OrderCreated":   reservations.OrderCreatedHandler{TopicARN: cfg.TopicARN, TTL: cfg.ReservationTTL},
			"OrderConfirmed": tickets.OrderConfirmedHandler{TopicARN: cfg.TopicARN, Logger: logger},
			"OrderCancelled": reservations.OrderCancelledHandler{},
		},
	})
	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := consumer.Run(ctx); err != nil && !errors.Is(err, context.Canceled) {
			logger.Error("consumer_stopped", "error", err.Error())
		}
	}()

	expirer := reservations.Expirer{Pool: workerPool, TopicARN: cfg.TopicARN,
		Interval: cfg.Expiry.Interval, BatchSize: cfg.Expiry.BatchSize, BatchPause: cfg.Expiry.BatchPause, Logger: logger}
	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := expirer.Run(ctx); err != nil && !errors.Is(err, context.Canceled) {
			logger.Error("expirer_stopped", "error", err.Error())
		}
	}()

	catalogHandler := catalog.NewHandler(catalog.NewRepository(appPool))
	mux := http.NewServeMux()
	mux.Handle("/health/", app.NewHealthHandler(appPool, app.AllUp(relay, consumer)))
	mux.Handle("/events", catalogHandler)
	mux.Handle("/events/", catalogHandler)

	err = app.Run(ctx, cfg, logger, mux)
	cancel() // also stops the relay when the HTTP server fails on its own
	wg.Wait()
	return err
}

// healthcheck probes the liveness endpoint; used by the container HEALTHCHECK (no shell in distroless).
func healthcheck() int {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	client := http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%s/health/live", port))
	if err != nil {
		return 1
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
