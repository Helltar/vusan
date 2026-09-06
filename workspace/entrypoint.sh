#!/bin/bash
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# the controller is the only role this image starts on its own. a workspace container is given `sleep`
# as its entrypoint and runs nothing privileged at any point in its life; the trusted helpers name their
# own script. narrow enough that a controller bug reaches its own state and the docker CLI, and nothing else.
exec deno run --no-prompt \
  --allow-net=0.0.0.0:8080 \
  --allow-read=/app,/state,/run,/tmp \
  --allow-write=/state,/tmp \
  --allow-run=docker \
  --allow-env=WORKSPACE_* \
  --allow-sys=statfs \
  /app/main.ts
