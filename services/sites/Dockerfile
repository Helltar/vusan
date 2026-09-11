# the same pinned base as the workspace service, so both move together.
FROM denoland/deno:debian-2.9.6@sha256:2014dc167ece617ef7e7ba40631ac2234c59e75ce693e7cc2dc2602b3c87859d

ENV DENO_DIR=/tmp/deno-cache

WORKDIR /app
COPY deno.json *.ts ./
RUN deno check main.ts

# a fixed uid of our own: the published tree is a host directory the operator creates and chowns, and
# that number is in the docs. Relying on the base image's `deno` user would move it on a base bump.
RUN groupadd --gid 1000 sites \
    && useradd --uid 1000 --gid 1000 --home-dir /app --shell /bin/sh sites \
    && mkdir -p /data \
    && chown 1000:1000 /data

USER 1000:1000
EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["deno", "eval", "--allow-net=127.0.0.1:8090", "Deno.exit((await fetch('http://127.0.0.1:8090/health')).ok ? 0 : 1)"]

CMD ["deno", "run", \
    "--allow-net=0.0.0.0:8090", \
    "--allow-read=/app,/data", \
    "--allow-write=/data", \
    "--allow-env=SITES_*", \
    "--allow-sys=statfs", \
    "main.ts"]
