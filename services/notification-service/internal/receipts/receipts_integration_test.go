//go:build integration

package receipts_test

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/ticketpro/notification-service/internal/platform/envelope"
	"github.com/ticketpro/notification-service/internal/platform/messaging"
	"github.com/ticketpro/notification-service/internal/receipts"
	"github.com/ticketpro/notification-service/internal/testdb"
)

func s3Client(t *testing.T) *s3.Client {
	t.Helper()
	ctx := context.Background()
	c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{
			Image: "ministackorg/ministack:1.5.40", ExposedPorts: []string{"4566/tcp"},
			WaitingFor: wait.ForHTTP("/_ministack/health").WithPort("4566/tcp").WithStartupTimeout(90 * time.Second),
		},
		Started: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Terminate(ctx) })
	endpoint, err := c.PortEndpoint(ctx, "4566/tcp", "http")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion("us-east-1"),
		config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")),
		config.WithBaseEndpoint(endpoint), config.WithHTTPClient(&http.Client{Timeout: 5 * time.Second}))
	if err != nil {
		t.Fatal(err)
	}
	return s3.NewFromConfig(cfg, func(o *s3.Options) { o.UsePathStyle = true })
}

func handle(t *testing.T, pool *pgxpool.Pool, h messaging.Handler, env envelope.Envelope) error {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := h.Handle(ctx, tx, env); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func ticketsIssued(t *testing.T, orderID string) envelope.Envelope {
	t.Helper()
	env, err := envelope.New(uuid.NewString(), "TicketsIssued", 1, map[string]any{
		"orderId": orderID, "userId": "0193a6f0-1111-7000-8000-000000000001", "eventId": "0193a6f0-3333-7000-8000-000000000003",
		"eventName": "Festival de Verão 2026", "eventStartsAt": "2026-12-12T21:00:00.000Z",
		"sectorId": "0193a6f0-4444-7000-8000-000000000004", "sectorName": "Pista",
		"tickets": []map[string]string{{"ticketId": uuid.NewString(), "code": "K7Q2-9XMA-41PZ"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return env
}

func rows(t *testing.T, pool *pgxpool.Pool, orderID string) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), `SELECT count(*) FROM notifications WHERE order_id = $1`, orderID).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestTicketsIssued_StoresTheReceiptOnceAndRecordsTheNotification(t *testing.T) {
	pool := testdb.Pool(t)
	client := s3Client(t)
	ctx := context.Background()
	if _, err := client.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String("ticketpro-receipts")}); err != nil {
		t.Fatal(err)
	}
	h := receipts.TicketsIssuedHandler{Store: receipts.NewS3Store(client, "ticketpro-receipts")}
	order := uuid.NewString()
	env := ticketsIssued(t, order)
	for i := 0; i < 2; i++ { // redelivery rewrites the same object
		if err := handle(t, pool, h, env); err != nil {
			t.Fatal(err)
		}
	}
	obj, err := client.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String("ticketpro-receipts"), Key: aws.String("receipts/" + order + ".json")})
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(obj.Body)
	var receipt map[string]any
	if err := json.Unmarshal(body, &receipt); err != nil {
		t.Fatal(err)
	}
	if receipt["orderId"] != order || receipt["eventName"] != "Festival de Verão 2026" || receipt["sectorName"] != "Pista" || receipt["issuedAt"] == nil {
		t.Fatalf("receipt %v", receipt)
	}
	if list, _ := receipt["tickets"].([]any); len(list) != 1 {
		t.Fatalf("tickets %v", receipt["tickets"])
	}
	if n := rows(t, pool, order); n != 1 {
		t.Fatalf("notifications %d, want 1", n)
	}
}

func TestTicketsIssued_S3FailureIsTransientAndWritesNothing(t *testing.T) {
	pool := testdb.Pool(t)
	h := receipts.TicketsIssuedHandler{Store: receipts.NewS3Store(s3Client(t), "no-such-bucket")}
	order := uuid.NewString()
	err := handle(t, pool, h, ticketsIssued(t, order))
	if err == nil || err == messaging.ErrStale {
		t.Fatalf("want a transient error, got %v", err)
	}
	if n := rows(t, pool, order); n != 0 {
		t.Fatalf("notifications %d, want 0", n)
	}
}

func TestOrderCancelled_RecordsTheNotification(t *testing.T) {
	pool := testdb.Pool(t)
	order := uuid.NewString()
	env, _ := envelope.New(uuid.NewString(), "OrderCancelled", 1, map[string]any{
		"orderId": order, "userId": "0193a6f0-1111-7000-8000-000000000001", "reason": "PAYMENT_FAILED", "cancelledAt": "2026-11-20T18:30:20.000Z",
	})
	if err := handle(t, pool, receipts.OrderCancelledHandler{}, env); err != nil {
		t.Fatal(err)
	}
	if n := rows(t, pool, order); n != 1 {
		t.Fatalf("notifications %d, want 1", n)
	}
}
