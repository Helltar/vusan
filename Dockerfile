FROM eclipse-temurin:21-jdk-alpine@sha256:c07bb82e004a166b5d3d63b84ad70e2e822f228505e2e0c0b8dfdc2890d22f13 AS build
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
# openjdk21-jre rather than its -headless half: alpine ships the jpeg codec (libjavajpeg) and the color
# management (liblcms) only there, and without them imageio reads and writes no jpeg at all, failing quietly
# wherever a picture is measured, compared or re-encoded. awt still runs headless, see the entrypoint.
RUN apk add --no-cache deno ffmpeg openjdk21-jre python3 && \
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

ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "vusan.jar"]
