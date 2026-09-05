# glibc supports the binary packages commonly installed into a workspace.
FROM denoland/deno:debian-2.9.6@sha256:2014dc167ece617ef7e7ba40631ac2234c59e75ce693e7cc2dc2602b3c87859d

ENV DENO_DIR=/tmp/deno-cache

RUN apt-get update && apt-get install -y --no-install-recommends \
        bash coreutils findutils procps psmisc util-linux \
        iptables ca-certificates \
        curl wget git jq zip unzip xz-utils \
        python3 python3-pip python3-venv \
        nodejs npm \
        ffmpeg imagemagick \
        sqlite3 ripgrep pandoc \
        chromium \
        fonts-noto-core fonts-noto-color-emoji \
    && rm -rf /var/lib/apt/lists/*

# no setuid path back to root. capabilities are already empty and no-new-privileges is set on every
# exec, but neither should be the single thing standing between the workspace user and root.
RUN find / -xdev -perm /6000 -type f -exec chmod u-s,g-s {} +

# only the supervisor receives a docker socket; workspace containers never do.
ARG DOCKER_CLI_VERSION=29.7.2
RUN curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_CLI_VERSION}.tgz" \
        | tar -xz -C /usr/local/bin --strip-components=1 docker/docker \
    && docker --version

# a newly created named volume inherits this directory's ownership.
RUN groupadd --gid 1000 workspace \
    && useradd --uid 1000 --gid 1000 --home-dir /work --shell /bin/bash workspace \
    && mkdir -p /work \
    && chown 1000:1000 /work \
    && chmod 700 /work

WORKDIR /app
COPY deno.json *.ts ./
COPY entrypoint.sh netpolicy.sh /usr/local/bin/
COPY profile.sh /etc/profile.d/vusan-workspace.sh
COPY chromium-flags.sh /etc/chromium.d/vusan

RUN deno check main.ts && chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/netpolicy.sh

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
