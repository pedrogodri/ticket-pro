package tickets

import (
	"regexp"
	"testing"
)

func TestNewCode_FormatAndAlphabet(t *testing.T) {
	re := regexp.MustCompile(`^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$`)
	seen := map[string]bool{}
	for i := 0; i < 1000; i++ {
		code, err := NewCode()
		if err != nil {
			t.Fatal(err)
		}
		if !re.MatchString(code) {
			t.Fatalf("code %q does not match XXXX-XXXX-XXXX without O/0/I/1", code)
		}
		seen[code] = true
	}
	if len(seen) < 1000 {
		t.Fatalf("collisions in 1000 codes: %d unique", len(seen))
	}
}
