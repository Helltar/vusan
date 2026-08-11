FROM eclipse-temurin:21-jdk-alpine@sha256:4fb80de7aeb277ad949cfbe89b4f504e50bb34c57fd908c5825236473d71e986 AS gradle-deps
WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies --configuration runtimeClasspath


FROM gradle-deps AS build
WORKDIR /app

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon shadowJar


FROM alpine:3.22@sha256:310c62b5e7ca5b08167e4384c68db0fd2905dd9c7493756d356e893909057601 AS runtime-base
WORKDIR /app

RUN apk add --no-cache deno ffmpeg openjdk21-jre-headless py3-pip && \
    pip install --pre --no-cache-dir --break-system-packages "yt-dlp[default]" && \
    addgroup -S -g 1000 vusan && \
    adduser -S -D -u 1000 -G vusan -h /home/vusan -s /sbin/nologin vusan && \
    mkdir -p /app/data && \
    chown -R vusan:vusan /app

VOLUME /app/data


FROM runtime-base AS runtime
WORKDIR /app

COPY --chown=vusan:vusan --from=build /app/build/libs/*-all.jar vusan.jar

USER vusan:vusan

# the app refreshes /tmp/health only while its getUpdates loop keeps cycling, so a stale file means
# the bot stopped polling telegram — which a process-level check cannot see, since polling runs on a
# scheduled executor the jvm happily outlives
#
# `test` rather than `[ … ]`: a CMD starting with `[` is read as the JSON exec form first, and only
# falls back to a shell command once that fails to parse
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD test $(( $(date +%s) - $(stat -c %Y /tmp/health 2>/dev/null || echo 0) )) -lt 90

ENTRYPOINT ["java", "-jar", "vusan.jar"]
