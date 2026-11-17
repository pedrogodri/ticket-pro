package docker

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
)

// fakeDaemon records calls and answers the Engine API endpoints the client uses.
type fakeDaemon struct {
	failStart bool
	mu        sync.Mutex
	calls     []string
	created   map[string]any
	connect   []map[string]any
}

func (d *fakeDaemon) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.calls = append(d.calls, r.Method+" "+r.URL.Path+"?"+r.URL.RawQuery)
	body, _ := io.ReadAll(r.Body)
	switch {
	case d.failStart && strings.HasSuffix(r.URL.Path, "/start"):
		http.Error(w, `{"message":"cannot start"}`, http.StatusInternalServerError)
	case strings.HasPrefix(r.URL.Path, "/containers/gone/"):
		http.Error(w, `{"message":"No such container: gone"}`, http.StatusNotFound)
	case r.Method == "GET" && r.URL.Path == "/containers/json":
		_, _ = w.Write([]byte(`[
			{"Id":"c2","Names":["/ticketpro-payment-service-2"],"Labels":{"com.docker.compose.container-number":"2"}},
			{"Id":"c1","Names":["/ticketpro-payment-service-1"],"Labels":{"com.docker.compose.container-number":"1"}}]`))
	case r.Method == "GET" && r.URL.Path == "/containers/c1/json":
		_, _ = w.Write([]byte(`{"Config":{"Image":"sha256:abc","Env":["A=1"],"Cmd":null,"Entrypoint":["/app"],"User":"app","WorkingDir":"/app",
			"Labels":{"com.docker.compose.project":"ticketpro","com.docker.compose.service":"payment-service","com.docker.compose.container-number":"1"},
			"Healthcheck":{"Test":["CMD","true"],"Interval":5000000000}},
			"HostConfig":{"RestartPolicy":{"Name":"no"}},
			"NetworkSettings":{"Networks":{"ticketpro-backend":{},"ticketpro-payment-service-data":{}}}}`))
	case r.Method == "POST" && r.URL.Path == "/containers/create":
		_ = json.Unmarshal(body, &d.created)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"Id":"new1"}`))
	case r.Method == "POST" && strings.HasPrefix(r.URL.Path, "/networks/") && strings.HasSuffix(r.URL.Path, "/connect"):
		var m map[string]any
		_ = json.Unmarshal(body, &m)
		m["network"] = strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/networks/"), "/connect")
		d.connect = append(d.connect, m)
		w.WriteHeader(http.StatusOK)
	case r.Method == "POST" && (strings.HasSuffix(r.URL.Path, "/start") || strings.HasSuffix(r.URL.Path, "/stop")):
		w.WriteHeader(http.StatusNoContent)
	case r.Method == "DELETE":
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "unexpected", http.StatusNotFound)
	}
}

func newClient(t *testing.T) (*Client, *fakeDaemon) {
	t.Helper()
	d := &fakeDaemon{}
	srv := httptest.NewServer(d)
	t.Cleanup(srv.Close)
	return &Client{HTTP: srv.Client(), BaseURL: srv.URL, Project: "ticketpro"}, d
}

func TestReplicasAreListedByComposeLabelsInNumberOrder(t *testing.T) {
	c, d := newClient(t)
	got, err := c.Replicas(context.Background(), "payment-service")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].ID != "c1" || got[0].Number != 1 || got[1].Number != 2 || got[1].Name != "ticketpro-payment-service-2" {
		t.Fatalf("replicas %+v", got)
	}
	q, _ := url.ParseQuery(strings.SplitN(d.calls[0], "?", 2)[1])
	if !strings.Contains(q.Get("filters"), "com.docker.compose.project=ticketpro") ||
		!strings.Contains(q.Get("filters"), "com.docker.compose.service=payment-service") {
		t.Fatalf("filters %q", q.Get("filters"))
	}
}

// Review Focus 5: the clone gets the next free number, the compose labels, and every network.
func TestCloneCreatesTheNextReplicaFromATemplate(t *testing.T) {
	c, d := newClient(t)
	if err := c.Clone(context.Background(), Container{ID: "c1", Number: 1}, "payment-service", 3); err != nil {
		t.Fatal(err)
	}
	if !contains(d.calls, "POST /containers/create?name=ticketpro-payment-service-3") {
		t.Fatalf("calls %v", d.calls)
	}
	labels := d.created["Labels"].(map[string]any)
	if d.created["Image"] != "sha256:abc" || labels["com.docker.compose.container-number"] != "3" ||
		labels["com.docker.compose.service"] != "payment-service" || labels["ticketpro.autoscaler"] != "true" {
		t.Fatalf("create body %v", d.created)
	}
	if d.created["Healthcheck"] == nil || d.created["Env"] == nil {
		t.Fatalf("healthcheck/env not copied: %v", d.created)
	}
	endpoints := d.created["NetworkingConfig"].(map[string]any)["EndpointsConfig"].(map[string]any)
	if len(endpoints)+len(d.connect) != 2 {
		t.Fatalf("networks: create %v, connect %v", endpoints, d.connect)
	}
	if !contains(d.calls, "POST /containers/new1/start?") {
		t.Fatalf("not started: %v", d.calls)
	}
}

func TestRemoveStopsThenDeletes(t *testing.T) {
	c, d := newClient(t)
	if err := c.Remove(context.Background(), "c2"); err != nil {
		t.Fatal(err)
	}
	if len(d.calls) != 2 || !strings.HasPrefix(d.calls[0], "POST /containers/c2/stop") || !strings.HasPrefix(d.calls[1], "DELETE /containers/c2") {
		t.Fatalf("calls %v", d.calls)
	}
}

// A clone that fails after create must not leave a container behind: the next tick would hit a
// 409 on the same name forever.
func TestFailedCloneRemovesTheCreatedContainer(t *testing.T) {
	c, d := newClient(t)
	d.failStart = true
	if err := c.Clone(context.Background(), Container{ID: "c1", Number: 1}, "payment-service", 3); err == nil {
		t.Fatal("want the start error")
	}
	if !contains(d.calls, "DELETE /containers/new1?force=true") {
		t.Fatalf("created container not removed: %v", d.calls)
	}
}

// A replica already gone (removed by someone else) counts as removed.
func TestRemoveOfAMissingContainerSucceeds(t *testing.T) {
	c, _ := newClient(t)
	if err := c.Remove(context.Background(), "gone"); err != nil {
		t.Fatalf("want nil for a missing container, got %v", err)
	}
}

func TestDaemonErrorIsReturned(t *testing.T) {
	c, d := newClient(t)
	d.failStart = true
	if err := c.Clone(context.Background(), Container{ID: "c1", Number: 1}, "payment-service", 3); err == nil {
		t.Fatal("want error from a non-2xx answer")
	}
}

func contains(list []string, prefix string) bool {
	for _, s := range list {
		if strings.HasPrefix(s, prefix) {
			return true
		}
	}
	return false
}
