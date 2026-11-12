// Package testenv starts the containers the platform integration tests need: Postgres 16 with the
// platform tables and MiniStack. One container of each is shared per test binary; Postgres tables are
// truncated on every call. Not vendored into services (scripts/sync-shared.sh skips internal/).
package testenv

import (
	"context"
	_ "embed"
	"fmt"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/moby/moby/client"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
)

const (
	postgresImage  = "postgres:16-alpine"
	ministackImage = "ministackorg/ministack:1.5.40"
	region         = "us-east-1"
	// ClientTimeout bounds every AWS call in tests, so a paused broker fails fast (P12).
	ClientTimeout = 2 * time.Second
)

//go:embed platform.sql
var platformSQL string

var (
	pgOnce sync.Once
	pgDSN  string
	pgErr  error

	msOnce     sync.Once
	msEndpoint string
	msID       string
	msErr      error
)

// Postgres returns a pool on the shared Postgres container with empty platform tables.
func Postgres(t *testing.T) *pgxpool.Pool {
	t.Helper()
	ctx := context.Background()
	pgOnce.Do(func() {
		c, err := tcpostgres.Run(ctx, postgresImage,
			tcpostgres.WithDatabase("platform"),
			tcpostgres.WithUsername("platform"),
			tcpostgres.WithPassword("platform"),
			tcpostgres.BasicWaitStrategies(),
		)
		if err != nil {
			pgErr = err
			return
		}
		pgDSN, pgErr = c.ConnectionString(ctx, "sslmode=disable")
		if pgErr != nil {
			return
		}
		pool, err := pgxpool.New(ctx, pgDSN)
		if err != nil {
			pgErr = err
			return
		}
		defer pool.Close()
		_, pgErr = pool.Exec(ctx, platformSQL)
	})
	if pgErr != nil {
		t.Fatalf("postgres container: %v", pgErr)
	}
	pool, err := pgxpool.New(ctx, pgDSN)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if _, err := pool.Exec(ctx, "TRUNCATE outbox, processed_messages, effects RESTART IDENTITY"); err != nil {
		t.Fatal(err)
	}
	return pool
}

// MiniStack returns an AWS config pointing at the shared MiniStack container and the container id
// (for Pause/Unpause). AWS SDK retries are disabled (RFC 0003 §5.5).
func MiniStack(t *testing.T) (aws.Config, string) {
	t.Helper()
	ctx := context.Background()
	msOnce.Do(func() {
		c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
			ContainerRequest: testcontainers.ContainerRequest{
				Image:        ministackImage,
				Env:          map[string]string{"MINISTACK_REGION": region},
				ExposedPorts: []string{"4566/tcp"},
				WaitingFor:   wait.ForHTTP("/_ministack/health").WithPort("4566/tcp").WithStartupTimeout(90 * time.Second),
			},
			Started: true,
		})
		if err != nil {
			msErr = err
			return
		}
		endpoint, err := c.PortEndpoint(ctx, "4566/tcp", "http")
		if err != nil {
			msErr = err
			return
		}
		msEndpoint, msID = endpoint, c.GetContainerID()
	})
	if msErr != nil {
		t.Fatalf("ministack container: %v", msErr)
	}
	cfg, err := config.LoadDefaultConfig(ctx,
		config.WithRegion(region),
		config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")),
		config.WithBaseEndpoint(msEndpoint),
		config.WithRetryMaxAttempts(1),
		config.WithHTTPClient(&http.Client{Timeout: ClientTimeout}),
	)
	if err != nil {
		t.Fatal(err)
	}
	return cfg, msID
}

// Pause freezes a container (same effect as `docker pause`, the F6 scenario) through the Docker API,
// so it also works when the tests run inside a container without the docker CLI (TOOLCHAIN=docker).
func Pause(t *testing.T, id string) {
	t.Helper()
	dockerClient(t, func(ctx context.Context, c *testcontainers.DockerClient) error {
		_, err := c.ContainerPause(ctx, id, client.ContainerPauseOptions{})
		return err
	})
}

// Unpause resumes a container paused by Pause.
func Unpause(t *testing.T, id string) {
	t.Helper()
	dockerClient(t, func(ctx context.Context, c *testcontainers.DockerClient) error {
		_, err := c.ContainerUnpause(ctx, id, client.ContainerUnpauseOptions{})
		return err
	})
}

func dockerClient(t *testing.T, op func(context.Context, *testcontainers.DockerClient) error) {
	t.Helper()
	ctx := context.Background()
	c, err := testcontainers.NewDockerClientWithOpts(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()
	if err := op(ctx, c); err != nil {
		t.Fatal(err)
	}
}

// Queue creates a queue with a DLQ (redrive after maxReceiveCount) and returns both URLs.
func Queue(t *testing.T, client *sqs.Client, name string, maxReceiveCount int) (queueURL, dlqURL string) {
	t.Helper()
	ctx := context.Background()
	name = fmt.Sprintf("%s-%d", name, time.Now().UnixNano())
	dlq, err := client.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(name + "-dlq")})
	if err != nil {
		t.Fatal(err)
	}
	dlqArn := QueueArn(t, client, *dlq.QueueUrl)
	q, err := client.CreateQueue(ctx, &sqs.CreateQueueInput{
		QueueName: aws.String(name),
		Attributes: map[string]string{
			"VisibilityTimeout": "30",
			"RedrivePolicy":     fmt.Sprintf(`{"deadLetterTargetArn":"%s","maxReceiveCount":"%d"}`, dlqArn, maxReceiveCount),
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return *q.QueueUrl, *dlq.QueueUrl
}

// QueueArn returns the ARN of a queue.
func QueueArn(t *testing.T, client *sqs.Client, url string) string {
	t.Helper()
	out, err := client.GetQueueAttributes(context.Background(), &sqs.GetQueueAttributesInput{
		QueueUrl: aws.String(url), AttributeNames: []sqstypes.QueueAttributeName{"QueueArn"},
	})
	if err != nil {
		t.Fatal(err)
	}
	return out.Attributes["QueueArn"]
}

// Topic creates a topic subscribed by queueURL with raw message delivery and returns the topic ARN.
func Topic(t *testing.T, snsClient *sns.Client, sqsClient *sqs.Client, name, queueURL string) string {
	t.Helper()
	ctx := context.Background()
	topic, err := snsClient.CreateTopic(ctx, &sns.CreateTopicInput{Name: aws.String(fmt.Sprintf("%s-%d", name, time.Now().UnixNano()))})
	if err != nil {
		t.Fatal(err)
	}
	_, err = snsClient.Subscribe(ctx, &sns.SubscribeInput{
		TopicArn:   topic.TopicArn,
		Protocol:   aws.String("sqs"),
		Endpoint:   aws.String(QueueArn(t, sqsClient, queueURL)),
		Attributes: map[string]string{"RawMessageDelivery": "true"},
	})
	if err != nil {
		t.Fatal(err)
	}
	return *topic.TopicArn
}

// Count returns the number of rows matching a SQL count query.
func Count(t *testing.T, pool *pgxpool.Pool, sql string, args ...any) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), sql, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// QueueDepth returns visible + in-flight messages of a queue.
func QueueDepth(t *testing.T, client *sqs.Client, url string) int {
	t.Helper()
	out, err := client.GetQueueAttributes(context.Background(), &sqs.GetQueueAttributesInput{
		QueueUrl: aws.String(url),
		AttributeNames: []sqstypes.QueueAttributeName{
			"ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	var visible, hidden int
	_, _ = fmt.Sscan(out.Attributes["ApproximateNumberOfMessages"], &visible)
	_, _ = fmt.Sscan(out.Attributes["ApproximateNumberOfMessagesNotVisible"], &hidden)
	return visible + hidden
}
