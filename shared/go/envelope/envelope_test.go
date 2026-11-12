package envelope

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/google/uuid"
)

// contractsDir is the repository's source of truth; this test is not vendored into services.
const contractsDir = "../../../contracts"

func TestP02_NewSerializesExactlySixKeys(t *testing.T) {
	env, err := New("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "OrderCreated", 1, map[string]any{"orderId": "o-1"})
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(env)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		t.Fatal(err)
	}
	if len(fields) != 6 {
		t.Fatalf("want 6 keys, got %d: %s", len(fields), raw)
	}
	for _, k := range []string{"messageId", "correlationId", "type", "version", "occurredAt", "payload"} {
		if _, ok := fields[k]; !ok {
			t.Fatalf("missing key %s in %s", k, raw)
		}
	}
	if _, err := uuid.Parse(env.MessageID); err != nil {
		t.Fatalf("messageId not a UUID: %v", err)
	}
	var occurredAt string
	_ = json.Unmarshal(fields["occurredAt"], &occurredAt)
	if !regexp.MustCompile(`^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$`).MatchString(occurredAt) {
		t.Fatalf("occurredAt format: %q", occurredAt)
	}

	var back Envelope
	if err := json.Unmarshal(raw, &back); err != nil {
		t.Fatal(err)
	}
	if back.MessageID != env.MessageID || back.CorrelationID != env.CorrelationID || back.Type != env.Type ||
		back.Version != env.Version || !back.OccurredAt.Equal(env.OccurredAt) || string(back.Payload) != string(env.Payload) {
		t.Fatalf("round trip changed the envelope:\n%+v\n%+v", env, back)
	}
}

func TestP03_UnwrapAcceptsSNSAndRawAndRejectsGarbage(t *testing.T) {
	raw := `{"messageId":"0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55","correlationId":"c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c","type":"Ping","version":1,"occurredAt":"2026-11-20T18:30:12.345Z","payload":{"a":1}}`
	quoted, _ := json.Marshal(raw)
	sns := `{"Type":"Notification","MessageId":"x","TopicArn":"arn:aws:sns:us-east-1:000000000000:t","Message":` + string(quoted) + `}`

	fromRaw, err := Unwrap([]byte(raw))
	if err != nil {
		t.Fatalf("raw: %v", err)
	}
	fromSNS, err := Unwrap([]byte(sns))
	if err != nil {
		t.Fatalf("sns: %v", err)
	}
	if fromRaw.MessageID != fromSNS.MessageID || fromRaw.Type != "Ping" || string(fromSNS.Payload) != `{"a":1}` {
		t.Fatalf("different envelopes: %+v vs %+v", fromRaw, fromSNS)
	}

	for _, body := range []string{`{"Type":"Notification","Message":"x"}`, `[]`, `not json`} {
		if _, err := Unwrap([]byte(body)); !errors.Is(err, ErrPoison) {
			t.Fatalf("body %q: want ErrPoison, got %v", body, err)
		}
	}
}

func TestP04_ValidatorAgainstContracts(t *testing.T) {
	v, err := NewValidator(contractsDir)
	if err != nil {
		t.Fatal(err)
	}

	valid, _ := filepath.Glob(filepath.Join(contractsDir, "examples", "*.json"))
	invalid, _ := filepath.Glob(filepath.Join(contractsDir, "examples", "invalid", "*.json"))
	if len(valid) == 0 || len(invalid) == 0 {
		t.Fatalf("no examples found (valid %d, invalid %d)", len(valid), len(invalid))
	}
	for _, f := range valid {
		if err := v.Validate(read(t, f)); err != nil {
			t.Errorf("%s should be valid: %v", filepath.Base(f), err)
		}
	}
	for _, f := range invalid {
		err := v.Validate(read(t, f))
		if !errors.Is(err, ErrPoison) {
			t.Errorf("%s should be rejected as poison, got %v", filepath.Base(f), err)
		}
	}

	orderCreated := string(read(t, filepath.Join(contractsDir, "examples", "OrderCreated.v1.json")))
	v3 := strings.Replace(orderCreated, `"version": 1`, `"version": 3`, 1)
	if err := v.Validate([]byte(v3)); !errors.Is(err, ErrUnsupportedVersion) {
		t.Fatalf("version 3: want ErrUnsupportedVersion, got %v", err)
	}
	unknown := strings.Replace(orderCreated, `"type": "OrderCreated"`, `"type": "NoSuchEvent"`, 1)
	if err := v.Validate([]byte(unknown)); !errors.Is(err, ErrUnsupportedVersion) {
		t.Fatalf("unknown type: want ErrUnsupportedVersion, got %v", err)
	}
	missing := strings.Replace(orderCreated, `"quantity"`, `"qty"`, 1)
	if err := v.Validate([]byte(missing)); !errors.Is(err, ErrInvalidMessage) {
		t.Fatalf("schema violation: want ErrInvalidMessage, got %v", err)
	}
}

func read(t *testing.T, path string) []byte {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
