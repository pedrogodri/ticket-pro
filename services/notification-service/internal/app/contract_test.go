package app

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/ticketpro/notification-service/internal/platform/envelope"
)

// Every vendored example of the events this service produces or consumes validates against the
// vendored schemas (RFC 0001 §6).
func TestVendoredContractExamplesAreValid(t *testing.T) {
	v, err := envelope.NewValidator("../../contracts")
	if err != nil {
		t.Fatal(err)
	}
	files, _ := filepath.Glob("../../contracts/examples/*.json")
	if len(files) == 0 {
		t.Fatal("no vendored examples; run scripts/sync-shared.sh")
	}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		if err := v.Validate(raw); err != nil {
			t.Errorf("%s: %v", filepath.Base(f), err)
		}
	}
}
