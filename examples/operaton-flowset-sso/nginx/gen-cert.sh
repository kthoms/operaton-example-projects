#!/usr/bin/env bash
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)/certs"
mkdir -p "$dir"
openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout "$dir/localhost.key" -out "$dir/localhost.crt" \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost"
echo "Wrote $dir/localhost.{crt,key}"
