FROM alpine:3.22@sha256:310c62b5e7ca5b08167e4384c68db0fd2905dd9c7493756d356e893909057601 AS base
WORKDIR /app

ENV DENO_DIR=/deno-dir

RUN apk add --no-cache deno && \
    addgroup -S -g 1000 sandbox && \
    adduser -S -D -u 1000 -G sandbox -h /home/sandbox -s /sbin/nologin sandbox

# matplotlib glyph-fallback fonts. CJK is large, so pull it from the package and
# keep only the Sans Regular face; emoji (monochrome) and symbols are vendored.
RUN mkdir -p /fonts && \
    apk add --no-cache --virtual .cjk font-noto-cjk && \
    cp /usr/share/fonts/noto/NotoSansCJK-Regular.ttc /fonts/ && \
    apk del .cjk
COPY fonts/*.ttf /fonts/

COPY deno.json packages.ts warmup.ts worker.ts main.ts ./

# Resolve module deps and download every Pyodide wheel into ./node_modules so
# the running container needs no network. The populated caches become part of
# the image; at runtime the service runs offline (no --allow-write).
RUN deno cache main.ts worker.ts warmup.ts && \
    deno task warmup && \
    chown -R sandbox:sandbox /app /deno-dir

USER sandbox:sandbox

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["deno", "run", "--allow-net", "--allow-read", "--allow-env", "main.ts"]
