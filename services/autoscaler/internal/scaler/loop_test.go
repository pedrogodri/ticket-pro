package scaler

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/ticketpro/autoscaler/internal/docker"
)

type fakeQueue struct {
	depth int
	err   error
}

func (q *fakeQueue) Depth(context.Context, string) (int, error) { return q.depth, q.err }

type fakeDocker struct {
	replicas []docker.Container
	cloned   []int
	removed  []string
	listErr  error
}

func (d *fakeDocker) Replicas(context.Context, string) ([]docker.Container, error) {
	return append([]docker.Container(nil), d.replicas...), d.listErr
}

func (d *fakeDocker) Clone(_ context.Context, tmpl docker.Container, _ string, n int) error {
	if tmpl.Number != d.replicas[0].Number {
		return errors.New("template must be an existing replica")
	}
	d.cloned = append(d.cloned, n)
	d.replicas = append(d.replicas, docker.Container{ID: "new", Number: n})
	return nil
}

func (d *fakeDocker) Remove(_ context.Context, id string) error {
	d.removed = append(d.removed, id)
	return nil
}

func replicas(numbers ...int) []docker.Container {
	out := make([]docker.Container, len(numbers))
	for i, n := range numbers {
		out[i] = docker.Container{ID: "c" + string(rune('0'+n)), Number: n}
	}
	return out
}

func newLoop(q Queue, d Docker, logs io.Writer) *Loop {
	return &Loop{
		Targets: []Target{{Service: "payment-service", Queue: "ticketpro-payment-service"}},
		Policy:  policy, Queue: q, Docker: d, Interval: 10 * time.Millisecond,
		Logger: slog.New(slog.NewJSONHandler(logs, nil)), Clock: func() time.Time { return time.Unix(1_800_000_000, 0) },
	}
}

func TestLoopScalesUpWithTheNextNumbers(t *testing.T) {
	d := &fakeDocker{replicas: replicas(1, 2)}
	var logs strings.Builder
	newLoop(&fakeQueue{depth: 22}, d, &logs).RunOnce(context.Background())
	if len(d.cloned) != 3 || d.cloned[0] != 3 || d.cloned[2] != 5 {
		t.Fatalf("cloned %v, want [3 4 5]", d.cloned)
	}
	if !strings.Contains(logs.String(), `"msg":"scale_decision"`) || !strings.Contains(logs.String(), `"depth":22`) ||
		!strings.Contains(logs.String(), `"current":2`) || !strings.Contains(logs.String(), `"desired":5`) {
		t.Fatalf("logs %s", logs.String())
	}
}

// Review Focus 4: scale down removes the highest numbers, never below MIN.
func TestLoopScalesDownRemovingTheHighestNumbers(t *testing.T) {
	d := &fakeDocker{replicas: replicas(1, 2, 3, 4, 5)}
	l := newLoop(&fakeQueue{depth: 0}, d, io.Discard)
	now := time.Unix(1_800_000_000, 0)
	l.Clock = func() time.Time { return now }
	l.RunOnce(context.Background()) // starts the cooldown
	now = now.Add(policy.Cooldown)
	l.RunOnce(context.Background())
	if len(d.removed) != 3 || d.removed[0] != "c5" || d.removed[2] != "c3" {
		t.Fatalf("removed %v, want [c5 c4 c3]", d.removed)
	}
}

// Review Focus 3: a failing queue or daemon changes nothing in that cycle.
func TestLoopErrorsChangeNothing(t *testing.T) {
	d := &fakeDocker{replicas: replicas(1, 2)}
	var logs strings.Builder
	newLoop(&fakeQueue{err: errors.New("sqs down")}, d, &logs).RunOnce(context.Background())
	d2 := &fakeDocker{replicas: replicas(1, 2), listErr: errors.New("docker down")}
	newLoop(&fakeQueue{depth: 22}, d2, &logs).RunOnce(context.Background())
	if len(d.cloned)+len(d.removed)+len(d2.cloned)+len(d2.removed) != 0 {
		t.Fatal("an error cycle must not scale")
	}
	if strings.Count(logs.String(), `"msg":"scale_check_failed"`) != 2 {
		t.Fatalf("logs %s", logs.String())
	}
}

func TestLoopRunKeepsGoingUntilCancelled(t *testing.T) {
	q := &fakeQueue{err: errors.New("sqs down")}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Millisecond)
	defer cancel()
	if err := newLoop(q, &fakeDocker{replicas: replicas(1, 2)}, io.Discard).Run(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Run returned %v", err)
	}
}
