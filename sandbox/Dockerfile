# Pure-Python document libraries (python-docx, fpdf2, pypdf, defusedxml) that the
# Pyodide distribution does not ship. Downloaded here and unpacked onto sys.path
# at runtime in worker.ts, so the offline sandbox can create and edit docx/pdf.
FROM python:3.14-alpine AS wheels
WORKDIR /wheels-src
COPY extra-wheels.txt .
RUN pip download --no-deps --dest /wheels -r extra-wheels.txt

# the official image pins the exact Deno version; alpine variant keeps apk for the font step below
FROM denoland/deno:alpine-2.9.6@sha256:aa665f8777136863b5b8a0445a5cdfccff8103b5f40c9a877de5276b04facb1e AS base
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

# the document-library wheels live under /app so the runtime read allowlist covers them
COPY --from=wheels /wheels /app/wheels

COPY deno.json deno.lock packages.ts warmup.ts worker.ts main.ts ./

# Resolve module deps and download every Pyodide wheel into ./node_modules so
# the running container needs no network. The populated caches become part of
# the image; at runtime the service runs offline (no --allow-write).
RUN deno ci && \
    deno cache --frozen main.ts worker.ts warmup.ts && \
    deno task warmup && \
    chown -R deno:deno /app /deno-dir

USER deno:deno

EXPOSE 8080

ENTRYPOINT ["deno", "run", "--allow-net=0.0.0.0:8080", "--allow-read=/app,/deno-dir,/fonts", "--allow-env", "main.ts"]
