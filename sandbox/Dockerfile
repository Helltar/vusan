# the official image pins the exact Deno version; alpine variant keeps apk for the font step below
FROM denoland/deno:alpine-2.8.2@sha256:e2a641dfee05544402cf0b149a41458bd373a8f47bff6544834177ded55d662d AS base
WORKDIR /app

# the image ships a non-root `deno` user (uid/gid 1000) used below
ENV DENO_DIR=/deno-dir

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
    chown -R deno:deno /app /deno-dir

USER deno:deno

EXPOSE 8080

ENTRYPOINT ["deno", "run", "--allow-net=0.0.0.0:8080", "--allow-read=/app,/deno-dir,/fonts", "--allow-env", "main.ts"]
