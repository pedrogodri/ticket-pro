package retry

import (
	"math/rand/v2"
	"testing"
	"time"
)

const (
	base = 200 * time.Millisecond
	cap_ = 30 * time.Second
)

func TestP01_DelayIsFullJitterBoundedByCap(t *testing.T) {
	zero := func() float64 { return 0 }
	almostOne := func() float64 { return 0.999999 }

	if got := Delay(5, base, cap_, zero); got != 0 {
		t.Fatalf("rnd=0: got %v, want 0", got)
	}
	cases := []struct {
		attempt int
		want    time.Duration
	}{{0, 200 * time.Millisecond}, {3, 1600 * time.Millisecond}, {10, 30 * time.Second}}
	for _, c := range cases {
		got := Delay(c.attempt, base, cap_, almostOne)
		if got > c.want || got < c.want-c.want/1000 {
			t.Fatalf("attempt %d with rnd≈1: got %v, want ≈%v", c.attempt, got, c.want)
		}
	}
	if got := Delay(1000, base, cap_, almostOne); got > cap_ {
		t.Fatalf("huge attempt must not overflow past cap: got %v", got)
	}
	for n := 0; n < 12; n++ {
		limit := min(cap_, base<<n)
		for i := 0; i < 1000; i++ {
			if got := Delay(n, base, cap_, rand.Float64); got < 0 || got > limit {
				t.Fatalf("attempt %d: %v outside [0, %v]", n, got, limit)
			}
		}
	}
}
