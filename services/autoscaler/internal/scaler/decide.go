// Package scaler decides replica counts from queue depth (ADR-0015).
package scaler

import "time"

// Policy is the scaling rule of one target.
type Policy struct {
	MsgsPerReplica int           // AUTOSCALER_MSGS_PER_REPLICA
	Min, Max       int           // AUTOSCALER_MIN_REPLICAS, AUTOSCALER_MAX_REPLICAS
	Cooldown       time.Duration // AUTOSCALER_COOLDOWN_S: how long the target must stay lower before scaling down
}

// Target is clamp(ceil(depth / MsgsPerReplica), Min, Max).
func (p Policy) Target(depth int) int {
	n := (depth + p.MsgsPerReplica - 1) / p.MsgsPerReplica
	return min(max(n, p.Min), p.Max)
}

// State is the hysteresis memory of one target: when the target first dropped below the current count.
type State struct {
	lowSince time.Time
}

// Decide returns the replica count to apply. Scaling up, and leaving an out-of-bounds count, is
// immediate; scaling down waits until the lower target has held for Cooldown, so a queue that drains
// for a moment between bursts does not make replicas flap.
func (s *State) Decide(p Policy, depth, current int, now time.Time) int {
	target := p.Target(depth)
	switch {
	case current > p.Max || current < p.Min:
		s.lowSince = time.Time{}
		return min(max(current, p.Min), p.Max)
	case target >= current:
		s.lowSince = time.Time{}
		return target
	case s.lowSince.IsZero():
		s.lowSince = now
		return current
	case now.Sub(s.lowSince) >= p.Cooldown:
		s.lowSince = time.Time{}
		return target
	default:
		return current
	}
}
