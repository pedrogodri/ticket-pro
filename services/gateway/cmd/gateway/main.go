// Command gateway is the single public entrypoint of Ticket Pro.
package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ticketpro/gateway/internal/app"
	"github.com/ticketpro/gateway/internal/jwtauth"
	"github.com/ticketpro/gateway/internal/proxy"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}

	cfg, err := app.LoadConfig(os.Getenv)
	if err != nil {
		app.NewLogger(os.Stderr, app.Config{ServiceName: "gateway"}).Error("invalid_config", "error", err.Error())
		os.Exit(1)
	}

	logger := app.NewLogger(os.Stdout, cfg)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	verifier := jwtauth.NewVerifier(jwtauth.VerifierConfig{
		Issuer: cfg.JWTIssuer, Audience: cfg.JWTAudience, ClockSkew: cfg.ClockSkew,
		CacheTTL: cfg.JWKSCacheTTL, RefetchMinInterval: 10 * time.Second,
	}, jwtauth.HTTPFetcher(cfg.JWKSURL, &http.Client{Timeout: cfg.UpstreamTimeout}))
	edge := proxy.New(proxy.Config{
		Routes: cfg.Routes,
		// RFC 0002 §5.5: the only routes without a token (refresh arrives in F3).
		Public:          map[string]bool{"POST /auth/register": true, "POST /auth/login": true},
		MaxBodyBytes:    cfg.MaxBodyBytes,
		UpstreamTimeout: cfg.UpstreamTimeout,
		Verifier:        verifier,
		Logger:          logger,
	})

	if err := app.Run(ctx, cfg, logger, edge); err != nil {
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
