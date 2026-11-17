// Package docker is a minimal Docker Engine API client over the daemon socket: list, clone and remove
// the replicas of a compose service (ADR-0015). It avoids the full Docker SDK on purpose.
package docker

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	labelProject = "com.docker.compose.project"
	labelService = "com.docker.compose.service"
	labelNumber  = "com.docker.compose.container-number"
	labelScaled  = "ticketpro.autoscaler"
)

// Client talks to the Docker daemon. BaseURL is "http://docker" when HTTP dials the unix socket.
type Client struct {
	HTTP    *http.Client
	BaseURL string
	Project string // COMPOSE_PROJECT_NAME
}

// NewUnixClient returns a client for a unix socket path such as /var/run/docker.sock.
func NewUnixClient(socket, project string, timeout time.Duration) *Client {
	transport := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", socket)
	}}
	return &Client{HTTP: &http.Client{Transport: transport, Timeout: timeout}, BaseURL: "http://docker", Project: project}
}

// Container is one running replica of a compose service.
type Container struct {
	ID     string
	Name   string
	Number int // com.docker.compose.container-number
}

// Replicas lists the running containers of service, ordered by container number.
func (c *Client) Replicas(ctx context.Context, service string) ([]Container, error) {
	filters, _ := json.Marshal(map[string][]string{"label": {labelProject + "=" + c.Project, labelService + "=" + service}})
	var out []struct {
		ID     string `json:"Id"`
		Names  []string
		Labels map[string]string
	}
	if err := c.do(ctx, http.MethodGet, "/containers/json?filters="+url.QueryEscape(string(filters)), nil, &out); err != nil {
		return nil, err
	}
	replicas := make([]Container, 0, len(out))
	for _, o := range out {
		n, _ := strconv.Atoi(o.Labels[labelNumber])
		name := ""
		if len(o.Names) > 0 {
			name = strings.TrimPrefix(o.Names[0], "/")
		}
		replicas = append(replicas, Container{ID: o.ID, Name: name, Number: n})
	}
	sort.Slice(replicas, func(i, j int) bool { return replicas[i].Number < replicas[j].Number })
	return replicas, nil
}

type inspect struct {
	Config struct {
		Image       string
		Env         []string
		Cmd         []string
		Entrypoint  []string
		User        string
		WorkingDir  string
		Labels      map[string]string
		Healthcheck json.RawMessage
	}
	HostConfig struct {
		RestartPolicy json.RawMessage
	}
	NetworkSettings struct {
		Networks map[string]json.RawMessage
	}
}

// Clone starts replica number of service from template: same image, env, command, healthcheck and
// networks (with the service name as DNS alias), and the compose labels so `docker compose ps` lists it.
func (c *Client) Clone(ctx context.Context, template Container, service string, number int) error {
	var src inspect
	if err := c.do(ctx, http.MethodGet, "/containers/"+template.ID+"/json", nil, &src); err != nil {
		return err
	}
	labels := make(map[string]string, len(src.Config.Labels)+1)
	for k, v := range src.Config.Labels {
		labels[k] = v
	}
	labels[labelNumber] = strconv.Itoa(number)
	labels[labelScaled] = "true"

	networks := make([]string, 0, len(src.NetworkSettings.Networks))
	for name := range src.NetworkSettings.Networks {
		networks = append(networks, name)
	}
	sort.Strings(networks)
	if len(networks) == 0 {
		return fmt.Errorf("template %s has no network", template.ID)
	}
	alias := map[string]any{"Aliases": []string{service}}

	body := map[string]any{
		"Image": src.Config.Image, "Env": src.Config.Env, "Cmd": src.Config.Cmd, "Entrypoint": src.Config.Entrypoint,
		"User": src.Config.User, "WorkingDir": src.Config.WorkingDir, "Labels": labels, "Healthcheck": src.Config.Healthcheck,
		"HostConfig":       map[string]any{"RestartPolicy": src.HostConfig.RestartPolicy},
		"NetworkingConfig": map[string]any{"EndpointsConfig": map[string]any{networks[0]: alias}},
	}
	name := fmt.Sprintf("%s-%s-%d", c.Project, service, number)
	var created struct {
		ID string `json:"Id"`
	}
	if err := c.do(ctx, http.MethodPost, "/containers/create?name="+url.QueryEscape(name), body, &created); err != nil {
		return err
	}
	err := c.finishClone(ctx, created.ID, networks[1:], alias)
	if err != nil {
		// A half-made clone keeps its name: remove it, or every later clone to the same number gets a 409.
		_ = c.do(context.WithoutCancel(ctx), http.MethodDelete, "/containers/"+created.ID+"?force=true", nil, nil)
	}
	return err
}

func (c *Client) finishClone(ctx context.Context, id string, networks []string, alias map[string]any) error {
	for _, n := range networks {
		if err := c.do(ctx, http.MethodPost, "/networks/"+url.PathEscape(n)+"/connect",
			map[string]any{"Container": id, "EndpointConfig": alias}, nil); err != nil {
			return err
		}
	}
	return c.do(ctx, http.MethodPost, "/containers/"+id+"/start", nil, nil)
}

// Remove stops a replica (SIGTERM, then 10 s grace: in-flight messages return to the queue) and deletes it.
// A container already gone (404) counts as removed.
func (c *Client) Remove(ctx context.Context, id string) error {
	if err := c.do(ctx, http.MethodPost, "/containers/"+id+"/stop?t=10", nil, nil); err != nil && !errors.Is(err, errNotFound) {
		return err
	}
	if err := c.do(ctx, http.MethodDelete, "/containers/"+id+"?force=true", nil, nil); err != nil && !errors.Is(err, errNotFound) {
		return err
	}
	return nil
}

var errNotFound = errors.New("not found")

func (c *Client) do(ctx context.Context, method, path string, in, out any) error {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.BaseURL+path, body)
	if err != nil {
		return err
	}
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("docker %s %s: %w", method, path, err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode >= 300 && resp.StatusCode != http.StatusNotModified {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		err := fmt.Errorf("docker %s %s: %d %s", method, path, resp.StatusCode, strings.TrimSpace(string(msg)))
		if resp.StatusCode == http.StatusNotFound {
			return errors.Join(errNotFound, err)
		}
		return err
	}
	if out != nil {
		return json.NewDecoder(resp.Body).Decode(out)
	}
	return nil
}
