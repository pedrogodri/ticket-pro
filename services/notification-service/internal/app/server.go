package app

import (
	"regexp"
	"strings"

	"context"
	"errors"
	"fmt"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"log/slog"
	"net/http"
	"time"
)

const shutdownTimeout = 10 * time.Second

// Run serves HTTP until ctx is cancelled, then shuts down gracefully.
func Run(ctx context.Context, cfg Config, logger *slog.Logger, handler http.Handler) error {
	srv := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           traced(handler, cfg.ServiceName),
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

// traced opens a server span per request (ADR-0012); the health probes are left out to keep traces readable.
func traced(h http.Handler, service string) http.Handler {
	return otelhttp.NewHandler(h, service,
		otelhttp.WithFilter(func(r *http.Request) bool { return !strings.HasPrefix(r.URL.Path, "/health/") }),
		otelhttp.WithSpanNameFormatter(func(_ string, r *http.Request) string { return r.Method + " " + route(r.URL.Path) }),
	)
}

// route collapses ids (UUIDs, numbers) to {id}: span names stay few and readable, and carry no data.
func route(path string) string {
	segments := strings.Split(path, "/")
	for i, seg := range segments {
		if idSegment.MatchString(seg) {
			segments[i] = "{id}"
		}
	}
	return strings.Join(segments, "/")
}

var idSegment = regexp.MustCompile(`^([0-9]+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$`)
