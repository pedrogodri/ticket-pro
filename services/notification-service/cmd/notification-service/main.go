// Command notification-service: sends purchase notifications and stores receipts in S3 for Ticket Pro.
package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/exaring/otelpgx"
	"github.com/ticketpro/notification-service/internal/platform/tracing"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ticketpro/notification-service/internal/app"
	"github.com/ticketpro/notification-service/internal/modules/receipts"
	"github.com/ticketpro/notification-service/internal/platform/envelope"
	"github.com/ticketpro/notification-service/internal/platform/logging"
	"github.com/ticketpro/notification-service/internal/platform/messaging"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}

	cfg, err := app.LoadConfig(os.Getenv)
	if err != nil {
		logging.New(os.Stderr, "notification-service", slog.LevelInfo).Error("invalid_config", "error", err.Error())
		os.Exit(1)
	}
	logger := logging.New(os.Stdout, cfg.ServiceName, cfg.LogLevel)

	serviceName := os.Getenv("OTEL_SERVICE_NAME")
	if serviceName == "" {
		serviceName = cfg.ServiceName
	}
	shutdownTracing, err := tracing.Setup(context.Background(), tracing.Config{
		ServiceName: serviceName, Endpoint: os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
	})
	if err != nil {
		logger.Error("tracing_setup_failed", "error", err.Error())
		os.Exit(1)
	}
	defer func() {
		flushCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = shutdownTracing(flushCtx)
	}()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := run(ctx, cfg, logger); err != nil {
		logger.Error("service_failed", "error", err.Error())
		os.Exit(1)
	}
}

// run serves the probes and consumes TicketsIssued / OrderCancelled. No outbox: the service publishes nothing.
func run(ctx context.Context, cfg app.Config, logger *slog.Logger) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	// Pools connect lazily: the service starts even if the database is still coming up (readiness says so).
	appPool, err := newPool(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("DATABASE_URL: %w", err)
	}
	defer appPool.Close()
	workerPool, err := newPool(ctx, cfg.DatabaseWorkerURL)
	if err != nil {
		return fmt.Errorf("DATABASE_WORKER_URL: %w", err)
	}
	defer workerPool.Close()

	awsCfg, err := messaging.NewAWSConfig(ctx, cfg.HTTPClientTimeout)
	if err != nil {
		return err
	}
	validator, err := envelope.NewValidator(cfg.ContractsDir)
	if err != nil {
		return fmt.Errorf("CONTRACTS_DIR: %w", err)
	}
	// MiniStack serves S3 path-style (bucket in the path, not in the host name).
	store := receipts.NewS3Store(s3.NewFromConfig(awsCfg, func(o *s3.Options) { o.UsePathStyle = true }), cfg.ReceiptsBucket)
	consumer := messaging.NewConsumer(messaging.ConsumerConfig{
		SQS: sqs.NewFromConfig(awsCfg), QueueURL: cfg.QueueURL, Name: cfg.ServiceName, Pool: workerPool,
		Validator: validator, Concurrency: cfg.Consumer.Concurrency, WaitTimeS: cfg.Consumer.WaitTimeS,
		RequestTimeout: cfg.HTTPClientTimeout, Logger: logger,
		Handlers: map[string]messaging.Handler{
			"TicketsIssued":  receipts.TicketsIssuedHandler{Store: store},
			"OrderCancelled": receipts.OrderCancelledHandler{},
		},
	})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := consumer.Run(ctx); err != nil && !errors.Is(err, context.Canceled) {
			logger.Error("consumer_stopped", "error", err.Error())
		}
	}()

	err = app.Run(ctx, cfg, logger, app.NewHealthHandler(appPool, consumer))
	cancel()
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

// newPool opens a pool whose queries are traced as client spans (otelpgx, ADR-0012).
func newPool(ctx context.Context, url string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, err
	}
	cfg.ConnConfig.Tracer = otelpgx.NewTracer()
	return pgxpool.NewWithConfig(ctx, cfg)
}
