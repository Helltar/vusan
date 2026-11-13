FROM eclipse-temurin:21-jdk-alpine@sha256:82801f9dcdcbad5a05082aba8c86d1da5b72ccb498e71318b1f91b5a477887b3 AS build
WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# deliberately no --mount=type=cache for ~/.gradle: buildkit cache mounts are never
# exported to the github actions cache, so this warm-up has to stay in the layer for
# image.yml to restore the wrapper and the dependencies with cache-from
RUN ./gradlew --no-daemon shadowJar

COPY src ./src
RUN ./gradlew --no-daemon shadowJar


FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS runtime
WORKDIR /app

# deno is here for yt-dlp, not for the sandbox service that runs its own: youtube's signature and
# nsig challenges are solved through it ("[jsc:deno] Solving JS challenges using deno"), and yt-dlp
# deprecates extraction without a JS runtime, warning that formats may be missing. it is the only
# runtime yt-dlp enables by default, so dropping the package silently degrades every youtube tool.
# its version is whatever alpine ships and is deliberately not held to the sandbox service's pin:
# yt-dlp needs a JS runtime, not a particular one, and alpine offers no version to pin to anyway.
RUN apk add --no-cache deno ffmpeg openjdk21-jre-headless python3 && \
    apk add --no-cache --virtual .pip py3-pip && \
    pip install --pre --no-cache-dir --break-system-packages "yt-dlp[default]" && \
    apk del .pip

RUN addgroup -S -g 1000 vusan && \
    adduser -S -D -u 1000 -G vusan -h /home/vusan -s /sbin/nologin vusan && \
    mkdir -p /app/data && \
    chown vusan:vusan /app/data

VOLUME /app/data

COPY --from=build /app/build/libs/*-all.jar vusan.jar

USER vusan:vusan

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD test $(( $(date +%s) - $(stat -c %Y /tmp/health 2>/dev/null || echo 0) )) -lt 90

ENTRYPOINT ["java", "-jar", "vusan.jar"]
