# The public half of the site host: TLS, the client-certificate check Cloudflare's origin pulls need,
# and static files off the published tree. The configuration is baked in rather than mounted, so a
# deployment is one compose file and one env file — no checkout on the machine that serves the internet.
FROM nginx:1.30.4-alpine@sha256:dc5069ad14f19660b141b21236140b91656bf89bbc3e2417c70ae650cd66104c

# only `SITES_*` is substituted into the template, so nginx's own `$variables` survive it.
ENV NGINX_ENVSUBST_FILTER=SITES_

COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY 18-site-domain-regex.envsh /docker-entrypoint.d/18-site-domain-regex.envsh
RUN chmod 0755 /docker-entrypoint.d/18-site-domain-regex.envsh

EXPOSE 443
