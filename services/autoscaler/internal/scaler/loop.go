package scaler

import (
	"context"
	"log/slog"
	"time"

	"github.com/ticketpro/autoscaler/internal/docker"
)

// Queue reports the backlog of a queue: visible + in-flight messages.
type Queue interface {
	Depth(ctx context.Context, queue string) (int, error)
}

// Docker lists, clones and removes the replicas of a compose service.
type Docker interface {
	Replicas(ctx context.Context, service string) ([]docker.Container, error)
	Clone(ctx context.Context, template docker.Container, service string, number int) error
	Remove(ctx context.Context, id string) error
}

// Target pairs a compose service with the queue it consumes (AUTOSCALER_TARGETS).
type Target struct {
	Service string
	Queue   string
}

// Loop checks every target each Interval and applies the replica count Decide returns.
type Loop struct {
	Targets  []Target
	Policy   Policy
	Queue    Queue
	Docker   Docker
	Interval time.Duration
	Logger   *slog.Logger
	Clock    func() time.Time

	states map[string]*State
}

// Run checks the targets until ctx is cancelled. Failures are logged and retried on the next tick.
func (l *Loop) Run(ctx context.Context) error {
	ticker := time.NewTicker(l.Interval)
	defer ticker.Stop()
	for {
		l.RunOnce(ctx)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

// RunOnce checks every target once.
func (l *Loop) RunOnce(ctx context.Context) {
	if l.states == nil {
		l.states = map[string]*State{}
	}
	for _, t := range l.Targets {
		if err := l.check(ctx, t); err != nil && ctx.Err() == nil {
			l.Logger.WarnContext(ctx, "scale_check_failed", "service", t.Service, "error", err.Error())
		}
	}
}

func (l *Loop) check(ctx context.Context, t Target) error {
	depth, err := l.Queue.Depth(ctx, t.Queue)
	if err != nil {
		return err
	}
	replicas, err := l.Docker.Replicas(ctx, t.Service)
	if err != nil {
		return err
	}
	if len(replicas) == 0 {
		return nil // nothing to clone from: the service is down, compose owns starting it
	}
	state, ok := l.states[t.Service]
	if !ok {
		state = &State{}
		l.states[t.Service] = state
	}
	current := len(replicas)
	desired := state.Decide(l.Policy, depth, current, l.Clock())
	l.Logger.InfoContext(ctx, "scale_decision", "service", t.Service, "depth", depth, "current", current, "desired", desired)

	next := replicas[len(replicas)-1].Number + 1
	for i := current; i < desired; i++ {
		if err := l.Docker.Clone(ctx, replicas[0], t.Service, next); err != nil {
			return err
		}
		next++
	}
	// Highest numbers first: the compose-created replicas (1..MIN) are the last to go.
	for i := current - 1; i >= desired; i-- {
		if err := l.Docker.Remove(ctx, replicas[i].ID); err != nil {
			return err
		}
	}
	return nil
}
