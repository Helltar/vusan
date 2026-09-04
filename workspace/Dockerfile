# Debian rather than alpine on purpose: a workspace installs its own dependencies, and on
# musl a manylinux wheel or a prebuilt node addon either compiles from source or does not
# install at all. glibc is what "pip install" and "npm i" expect to find.
FROM denoland/deno:debian-2.9.6@sha256:2014dc167ece617ef7e7ba40631ac2234c59e75ce693e7cc2dc2602b3c87859d

ENV DENO_DIR=/deno-dir \
    WORKSPACE_ROOT=/work

RUN apt-get update && apt-get install -y --no-install-recommends \
        bash coreutils findutils procps psmisc util-linux \
        iptables ca-certificates \
        curl wget git jq zip unzip xz-utils \
        python3 python3-pip python3-venv \
        nodejs npm \
        ffmpeg imagemagick \
        build-essential pkg-config \
        sqlite3 ripgrep pandoc \
        fonts-noto-core fonts-noto-cjk fonts-noto-color-emoji \
    && rm -rf /var/lib/apt/lists/*

# the engine client, and only the client — `container` mode drives an engine over a socket the
# supervisor is given, and never runs one. the same binary talks to podman, whose API is
# docker-compatible, so one CLI covers both.
ARG DOCKER_CLI_VERSION=29.7.2
RUN curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_CLI_VERSION}.tgz" \
        | tar -xz -C /usr/local/bin --strip-components=1 docker/docker \
    && docker --version

# the uid pool the registry hands out. it is baked into the image because the rootfs is
# read-only at runtime, so /etc/passwd cannot grow a user when a workspace is created.
# a workspace keeps its slot for life: the files on the volume are owned by that uid.
RUN for i in $(seq 0 255); do \
        uid=$((20000 + i)); \
        echo "ws$i:x:$uid:$uid::/work:/bin/bash" >> /etc/passwd; \
        echo "ws$i:x:$uid:" >> /etc/group; \
    done

WORKDIR /app
# every module, rather than a list that silently goes stale when one is added
COPY deno.json *.ts ./
COPY entrypoint.sh netpolicy.sh /usr/local/bin/
COPY profile.sh /etc/profile.d/vusan-workspace.sh

RUN deno check main.ts && chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/netpolicy.sh

VOLUME /work
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
