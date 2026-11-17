package scaler

import (
	"testing"
	"time"
)

var policy = Policy{MsgsPerReplica: 5, Min: 2, Max: 5, Cooldown: 30 * time.Second}

func TestDesiredIsCeilOfDepthClamped(t *testing.T) {
	cases := []struct{ depth, want int }{
		{0, 2}, {1, 2}, {10, 2}, {11, 3}, {15, 3}, {16, 4}, {25, 5}, {1000, 5},
	}
	for _, c := range cases {
		if got := policy.Target(c.depth); got != c.want {
			t.Errorf("depth %d: target %d, want %d", c.depth, got, c.want)
		}
	}
}

func TestScaleUpIsImmediate(t *testing.T) {
	var s State
	now := time.Unix(1_800_000_000, 0)
	if got := s.Decide(policy, 22, 2, now); got != 5 {
		t.Fatalf("desired %d, want 5", got)
	}
}

// Review Focus 1: a queue that empties for a moment does not scale down before the cooldown.
func TestScaleDownWaitsForTheCooldown(t *testing.T) {
	var s State
	now := time.Unix(1_800_000_000, 0)
	if got := s.Decide(policy, 0, 5, now); got != 5 {
		t.Fatalf("first low reading: desired %d, want 5", got)
	}
	if got := s.Decide(policy, 0, 5, now.Add(29*time.Second)); got != 5 {
		t.Fatalf("before cooldown: desired %d, want 5", got)
	}
	if got := s.Decide(policy, 0, 5, now.Add(30*time.Second)); got != 2 {
		t.Fatalf("after cooldown: desired %d, want 2", got)
	}
}

func TestPressureResetsTheCooldown(t *testing.T) {
	var s State
	now := time.Unix(1_800_000_000, 0)
	s.Decide(policy, 0, 5, now)
	s.Decide(policy, 25, 5, now.Add(20*time.Second)) // back under pressure
	if got := s.Decide(policy, 0, 5, now.Add(35*time.Second)); got != 5 {
		t.Fatalf("cooldown must restart after pressure: desired %d, want 5", got)
	}
}

// Review Focus 2: extra replicas created by hand are brought back within MAX right away.
func TestAboveMaxIsCorrectedImmediately(t *testing.T) {
	var s State
	if got := s.Decide(policy, 1000, 8, time.Unix(1_800_000_000, 0)); got != 5 {
		t.Fatalf("desired %d, want 5", got)
	}
	if got := s.Decide(policy, 0, 1, time.Unix(1_800_000_000, 0)); got != 2 {
		t.Fatalf("below MIN: desired %d, want 2", got)
	}
}
