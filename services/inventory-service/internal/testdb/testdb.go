//go:build integration

// Package testdb starts Postgres 16 for integration tests and applies this service's migrations/ in
// order, as Flyway does (placeholders filled with test passwords). One container per test binary.
package testdb

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
)

var (
	once sync.Once
	dsn  string
	err  error
)

// MigrationsDir is the service's migrations folder, found relative to this file.
func MigrationsDir() string {
	_, file, _, _ := runtime.Caller(0)
	return filepath.Join(filepath.Dir(file), "..", "..", "migrations")
}

// Pool returns a superuser pool on a fresh, fully migrated database shared by the test binary.
func Pool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	ctx := context.Background()
	once.Do(func() {
		c, e := tcpostgres.Run(ctx, "postgres:16-alpine", tcpostgres.WithDatabase("inventory"),
			tcpostgres.WithUsername("inventory_owner"), tcpostgres.WithPassword("owner"), tcpostgres.BasicWaitStrategies())
		if e != nil {
			err = e
			return
		}
		if dsn, err = c.ConnectionString(ctx, "sslmode=disable"); err != nil {
			return
		}
		err = migrate(ctx, dsn)
	})
	if err != nil {
		t.Fatalf("test database: %v", err)
	}
	pool, e := pgxpool.New(ctx, dsn)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(pool.Close)
	return pool
}

func migrate(ctx context.Context, dsn string) error {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return err
	}
	defer pool.Close()
	files, _ := filepath.Glob(filepath.Join(MigrationsDir(), "V*__*.sql"))
	sort.Strings(files)
	for _, f := range files {
		sql, err := os.ReadFile(f)
		if err != nil {
			return err
		}
		text := strings.NewReplacer("${app_password}", "app_test", "${worker_password}", "worker_test").Replace(string(sql))
		if _, err := pool.Exec(ctx, text); err != nil {
			return err
		}
	}
	return nil
}
