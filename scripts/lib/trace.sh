#!/usr/bin/env bash
# Narration for the live demo: one line per thing a script does (HTTP call and its answer, SNS publish,
# database check, docker action). Source it: `. scripts/lib/trace.sh`.
# Lines go to stderr, so functions whose stdout is captured (`T=$(new_user)`) stay clean. On when stderr
# is a terminal; DEMO_TRACE=0 turns it off, DEMO_TRACE=1 forces it (scripts/demo.sh `all` runs without it).
#   trace MSG       always prints (each SNS publish, each docker action)
#   trace_once MSG  skips a line equal to the previous trace_once line, so polling shows only changes
#   trace_http METHOD PATH REQ_BODY STATUS RESP_BODY [NOTE]
#                   "→ request" and "← status + answer"; a GET that repeats the previous answer is skipped
#   clip TEXT [N]   TEXT on one line, tokens and passwords masked, cut at N chars (default 90)

_TRACE_LAST="${TMPDIR:-/tmp}/ticketpro-trace.$$"
find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'ticketpro-trace.*' -mmin +60 -delete 2>/dev/null

_trace_on() {
  [ -z "${_TRACE_QUIET:-}" ] || return 1
  case "${DEMO_TRACE:-}" in 1) return 0 ;; 0) return 1 ;; esac
  [ -t 2 ]
}

_trace_print() {
  if [ -t 2 ]; then printf '\033[36m%s\033[0m\n' "$1" >&2; else printf '%s\n' "$1" >&2; fi
}

trace() { _trace_on || return 0; _trace_print "  · $*"; }

_trace_dedup() { # TEXT → prints TEXT unless it equals the previous deduplicated one
  [ -f "$_TRACE_LAST" ] && [ "$(cat "$_TRACE_LAST")" = "$1" ] && return 0
  printf '%s' "$1" > "$_TRACE_LAST"
  _trace_print "$1"
}

trace_once() { _trace_on || return 0; _trace_dedup "  · $*"; }

trace_http() {
  _trace_on || return 0
  local text="  → $1 $2${3:+  $(clip "$3" 100)}"$'\n'"    ← $4${6:+  $6}${5:+  $(clip "$5" 110)}"
  if [ "$1" = GET ]; then _trace_dedup "$text"; else _trace_print "$text"; fi
}

clip() {
  printf '%s' "$1" | tr '\n\r\t' '   ' | tr -s ' ' \
    | sed -E 's/"(accessToken|refreshToken|password)":"[^"]*"/"\1":"…"/g' | cut -c1-"${2:-90}"
}

# banner KIND TITLE [DETAIL...] → the key moment of a scenario, impossible to miss
#   fail = something dies, stops or fails (red) · ok = it recovered (green)
#   info = replicas, scale, counts (yellow)     · comp = compensation (magenta)
banner() {
  _trace_on || return 0
  local kind="$1" title="$2" color icon d; shift 2
  case "$kind" in
    fail) color='1;97;41'; icon='✖' ;; ok) color='1;30;42'; icon='✔' ;;
    comp) color='1;97;45'; icon='↺' ;; *) color='1;30;43'; icon='◆' ;;
  esac
  if [ -t 2 ]; then
    printf '\n\033[%sm  %s  %s  \033[0m\n' "$color" "$icon" "$title" >&2
    for d in "$@"; do printf '\033[1m     %s\033[0m\n' "$d" >&2; done
    printf '\n' >&2
  else
    printf '\n>>> %s %s\n' "$icon" "$title" >&2
    for d in "$@"; do printf '    %s\n' "$d" >&2; done
  fi
}

# trace_replicas SERVICE → every container of SERVICE with its state: ● running, ⏸ paused, ✖ dead
trace_replicas() {
  _trace_on || return 0
  local name state status mark
  docker ps -a --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-ticketpro}" \
    --filter "label=com.docker.compose.service=$1" --format '{{.Names}}|{{.State}}|{{.Status}}' | sort \
    | while IFS='|' read -r name state status; do
        case "$state" in
          running) mark=$'\033[32m● rodando  \033[0m' ;; paused) mark=$'\033[33m⏸ CONGELADA\033[0m' ;;
          *) mark=$'\033[1;31m✖ MORTA    \033[0m' ;;
        esac
        [ -t 2 ] || mark="[$state]"
        printf '     %s  %-34s %s\n' "$mark" "$name" "$status" >&2
      done
}

# trace_consumers SERVICE SINCE → how many messages each replica of SERVICE consumed since SINCE (RFC 3339)
trace_consumers() {
  _trace_on || return 0
  local id name k
  for id in $(docker ps -q --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-ticketpro}" \
      --filter "label=com.docker.compose.service=$1"); do
    name=$(docker inspect -f '{{.Name}}' "$id" | tr -d /)
    k=$(docker logs --since "$2" "$id" 2>&1 | grep -c '"message":"message_consumed"')
    printf '     %-34s consumiu %s mensagem(ns)\n' "$name" "$k" >&2
  done
}
