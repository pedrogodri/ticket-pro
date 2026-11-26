#!/usr/bin/env bash
# Opens the presentation layout in macOS Terminal.app (docs/apresentacao/guia-demo.md §0):
#   T1 demo   scripts/demo.sh (menu, with the live narration of every request)   left half
#   T2 logs   scripts/demo.sh logs: every message published/consumed, duplicates,   top right
#             rejections, broker drops and scaling, one short line each
#   T3 filas  scripts/queues.sh watch                                            bottom right
# With --browser it also opens the latest order's trace in Jaeger, pgAdmin, RedisInsight and SQS Admin
# (scripts/tools.sh up first). Usage: scripts/demo-terminals.sh [--browser]
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
command -v osascript >/dev/null || { echo "macOS only (needs osascript and Terminal.app)" >&2; exit 1; }

# open_window TITLE COMMAND POSITION(left|top-right|bottom-right) → a new Terminal.app window running COMMAND
open_window() {
  osascript - "$1" "cd '$ROOT' && clear && $2" "$3" <<'OSA' >/dev/null
on run argv
  set theTitle to item 1 of argv
  set theCommand to item 2 of argv
  set thePosition to item 3 of argv
  tell application "Terminal"
    activate
    set theTab to do script theCommand
    set custom title of theTab to theTitle
    set title displays custom title of theTab to true
  end tell
  try
    tell application "Finder" to set screenBounds to bounds of window of desktop
    set w to item 3 of screenBounds
    set h to item 4 of screenBounds
    if thePosition is "left" then
      set b to {0, 25, w div 2, h}
    else if thePosition is "top-right" then
      set b to {w div 2, 25, w, h div 2}
    else
      set b to {w div 2, h div 2, w, h}
    end if
    tell application "Terminal" to set bounds of front window to b
  end try
end run
OSA
}

open_window "T3 filas" "scripts/queues.sh watch" bottom-right
open_window "T2 logs" "scripts/demo.sh logs" top-right
open_window "T1 demo" "scripts/demo.sh" left
echo "Terminal.app: T1 demo (esquerda), T2 logs (direita, em cima), T3 filas (direita, embaixo)"

if [ "${1:-}" = --browser ]; then
  . scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
  _TRACE_QUIET=1
  order=$(sql orders "SELECT id FROM orders ORDER BY created_at DESC LIMIT 1")
  tp=$([ -n "$order" ] && sql orders "SELECT traceparent FROM outbox WHERE payload->>'orderId' = '$order' ORDER BY id LIMIT 1")
  jaeger="http://localhost:16686${tp:+/trace/$(echo "$tp" | cut -d- -f2)}"
  for url in "$jaeger" http://localhost:5050 http://localhost:5540 http://localhost:3999; do open "$url"; sleep 0.5; done
  echo "navegador: Jaeger (último pedido), pgAdmin, RedisInsight, SQS Admin"
fi
