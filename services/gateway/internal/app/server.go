package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"time"
)

const shutdownTimeout = 10 * time.Second

// Run serves HTTP until ctx is cancelled, then shuts down gracefully.
// The gateway answers its own probes; every other path goes to the edge handler (routing + JWT).
func Run(ctx context.Context, cfg Config, logger *slog.Logger, edge http.Handler) error {
	health := NewHealthHandler()
	mux := http.NewServeMux()
	mux.Handle("GET /health/live", health)
	mux.Handle("GET /health/ready", health)
	mux.Handle("/", edge)
	srv := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("http_server_started", "port", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}

	logger.Info("http_server_stopping")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	return srv.Shutdown(shutdownCtx)
}
