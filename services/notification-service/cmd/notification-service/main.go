// Command notification-service: sends purchase notifications and stores receipts in S3 for Ticket Pro.
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ticketpro/notification-service/internal/app"
	"github.com/ticketpro/notification-service/internal/platform/logging"
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

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := run(ctx, cfg, logger); err != nil {
		logger.Error("service_failed", "error", err.Error())
		os.Exit(1)
	}
}

// run serves the probes. The SQS consumers (TicketsIssued, OrderCancelled) arrive in F2 and will use
// the worker pool; this service has no outbox because it publishes nothing.
func run(ctx context.Context, cfg app.Config, logger *slog.Logger) error {
	// Pools connect lazily: the service starts even if the database is still coming up (readiness says so).
	appPool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("DATABASE_URL: %w", err)
	}
	defer appPool.Close()
	return app.Run(ctx, cfg, logger, app.NewHealthHandler(appPool, nil))
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
