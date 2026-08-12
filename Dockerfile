FROM eclipse-temurin:21-jdk-alpine@sha256:4fb80de7aeb277ad949cfbe89b4f504e50bb34c57fd908c5825236473d71e986 AS build
WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon shadowJar

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon shadowJar


FROM alpine:3.22@sha256:310c62b5e7ca5b08167e4384c68db0fd2905dd9c7493756d356e893909057601 AS runtime
WORKDIR /app

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
