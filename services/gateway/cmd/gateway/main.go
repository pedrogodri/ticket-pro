// Command gateway is the single public entrypoint of Ticket Pro.
package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ticketpro/gateway/internal/app"
	"github.com/ticketpro/gateway/internal/jwtauth"
	"github.com/ticketpro/gateway/internal/platform/envelope"
	"github.com/ticketpro/gateway/internal/platform/messaging"
	"github.com/ticketpro/gateway/internal/proxy"
	"github.com/ticketpro/gateway/internal/ratelimit"
	"github.com/ticketpro/gateway/internal/revocation"

	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/redis/go-redis/v9"
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
	redisOpts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		logger.Error("invalid_config", "error", err.Error())
		os.Exit(1)
	}
	redisOpts.DialTimeout, redisOpts.ReadTimeout, redisOpts.WriteTimeout = cfg.RedisTimeout, cfg.RedisTimeout, cfg.RedisTimeout
	redisOpts.MaxRetries = -1 // a slow Redis must fail open fast, not retry inside the request
	rdb := redis.NewClient(redisOpts)
	defer func() { _ = rdb.Close() }()
	revocations := revocation.Store{Client: rdb}

	edge := proxy.New(proxy.Config{
		Routes: cfg.Routes,
		// RFC 0002 §5.5: the only routes without a token (refresh arrives in F3).
		Public:          map[string]bool{"POST /auth/register": true, "POST /auth/login": true},
		MaxBodyBytes:    cfg.MaxBodyBytes,
		UpstreamTimeout: cfg.UpstreamTimeout,
		Verifier:        verifier,
		Revocations:     revocations,
		Limiter:         ratelimit.Limiter{Client: rdb, Clock: time.Now},
		Limits: proxy.Limits{
			Default: rule(cfg.Limits.Default), Public: rule(cfg.Limits.Public),
			Login: rule(cfg.Limits.Login), Orders: rule(cfg.Limits.Orders),
		},
		Logger: logger,
	})

	awsCfg, err := messaging.NewAWSConfig(ctx, cfg.HTTPClientTimeout)
	if err != nil {
		logger.Error("aws_config_failed", "error", err.Error())
		os.Exit(1)
	}
	validator, err := envelope.NewValidator(cfg.ContractsDir)
	if err != nil {
		logger.Error("contracts_load_failed", "error", err.Error())
		os.Exit(1)
	}
	consumer := revocation.NewConsumer(revocation.ConsumerConfig{
		Queue: sqs.NewFromConfig(awsCfg), QueueURL: cfg.QueueURL, Store: revocations,
		Validator: validator, WaitTimeS: cfg.SQSWaitTimeS, Logger: logger,
	})
	go func() {
		if err := consumer.Run(ctx); err != nil && !errors.Is(err, context.Canceled) {
			logger.Error("consumer_stopped", "error", err.Error())
		}
	}()

	health := app.NewHealthHandler(redisPinger{rdb}, consumer)
	if err := app.Run(ctx, cfg, logger, health, edge); err != nil {
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

// redisPinger adapts the Redis client to app.Pinger.
type redisPinger struct{ client *redis.Client }

func (p redisPinger) Ping(ctx context.Context) error { return p.client.Ping(ctx).Err() }

func rule(r app.Rule) ratelimit.Rule { return ratelimit.Rule{RPS: r.RPS, Burst: r.Burst} }
