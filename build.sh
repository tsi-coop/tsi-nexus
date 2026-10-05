#!/usr/bin/env bash
# Build the WAR and (re)start the stack. The Dockerfile copies target/tsi_nexus.war,
# so the WAR must be rebuilt before the image, or the container runs the previous build.
set -euo pipefail
cd "$(dirname "$0")"

mvn package -q
docker compose up -d --build
