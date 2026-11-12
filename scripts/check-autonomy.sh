#!/usr/bin/env bash
# Proves a service folder behaves like a standalone repository (ADR-0016):
#   1. copies it outside the monorepo and runs `make test` there;
#   2. builds its Docker image from that copy (context = the folder only);
#   3. checks the image runs as a non-root user.
# Usage: scripts/check-autonomy.sh services/<svc>
set -euo pipefail

src="${1:?usage: scripts/check-autonomy.sh services/<svc>}"
[ -d "$src" ] || { echo "not a directory: $src"; exit 2; }
name="$(basename "$src")"

workdir="$(mktemp -d "${TMPDIR:-/tmp}/autonomy-${name}.XXXXXX")"
cleanup() { rm -rf "${workdir:?}"; }
trap cleanup EXIT

# Copy without build/dependency outputs so nothing leaks from the monorepo checkout.
rsync -a \
  --exclude node_modules --exclude dist --exclude bin --exclude obj --exclude target \
  --exclude .env \
  "$src/" "$workdir/$name/"

echo "==> make test (in $workdir/$name)"
(cd "$workdir/$name" && make test)

image="ingressoja-autonomy/${name}:check"
echo "==> docker build ($image)"
docker build -q -t "$image" "$workdir/$name" >/dev/null

user="$(docker image inspect -f '{{.Config.User}}' "$image")"
case "$user" in
  "" | root | 0 | 0:0 | root:root)
    echo "FAIL image runs as root (Config.User='$user')"; exit 1 ;;
esac
echo "user=$user"
echo "autonomy OK: $name"
