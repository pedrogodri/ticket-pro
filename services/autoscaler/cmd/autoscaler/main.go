// Command autoscaler: scales consumer replicas from SQS queue depth for Ticket Pro.
package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sqs"

	"github.com/ticketpro/autoscaler/internal/app"
	"github.com/ticketpro/autoscaler/internal/docker"
	"github.com/ticketpro/autoscaler/internal/queue"
	"github.com/ticketpro/autoscaler/internal/scaler"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}

	cfg, err := app.LoadConfig(os.Getenv)
	if err != nil {
		app.NewLogger(os.Stderr, app.Config{ServiceName: "autoscaler"}).Error("invalid_config", "error", err.Error())
		os.Exit(1)
	}

	logger := app.NewLogger(os.Stdout, cfg)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	awsCfg, err := config.LoadDefaultConfig(ctx, config.WithHTTPClient(&http.Client{Timeout: cfg.HTTPClientTimeout}))
	if err != nil {
		logger.Error("aws_config_failed", "error", err.Error())
		os.Exit(1)
	}
	loop := &scaler.Loop{
		Targets: cfg.Targets, Policy: cfg.Policy, Interval: cfg.Interval, Logger: logger, Clock: time.Now,
		Queue:  queue.NewReader(sqs.NewFromConfig(awsCfg)),
		Docker: docker.NewUnixClient(cfg.DockerSocket, cfg.Project, cfg.HTTPClientTimeout*5),
	}
	logger.Info("autoscaler_started", "targets", fmt.Sprint(cfg.Targets), "min", cfg.Policy.Min, "max", cfg.Policy.Max,
		"msgsPerReplica", cfg.Policy.MsgsPerReplica, "cooldownS", cfg.Policy.Cooldown.Seconds())
	go func() { _ = loop.Run(ctx) }()

	if err := app.Run(ctx, cfg, logger); err != nil {
		logger.Error("server_failed", "error", err.Error())
		os.Exit(1)
	}
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
