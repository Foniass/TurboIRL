# Réplique locale du VPS pour le banc : MediaMTX (même version que le VPS) + le bond (même fichier que le dépôt
# turboirl-api). Une seule image, deux processus, mêmes ports : 8890 SRT (relais), 8891 UDP (bond), 9997 API.
FROM debian:13-slim
ARG MEDIAMTX_VERSION=1.21.1
RUN apt-get update && apt-get install -y --no-install-recommends python3 ca-certificates curl && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/mediamtx /opt/turboirl/bond /opt/mediamtx/recordings \
    && curl -fsSL "https://github.com/bluenviron/mediamtx/releases/download/v${MEDIAMTX_VERSION}/mediamtx_v${MEDIAMTX_VERSION}_linux_amd64.tar.gz" \
       | tar -xz -C /opt/mediamtx mediamtx
COPY bond.py /opt/turboirl/bond/bond.py
COPY mediamtx.yml.template /opt/mediamtx/mediamtx.yml.template
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
# tampons UDP comme sur le VPS (sysctl 90-turboirl.conf) : appliqués au démarrage si le conteneur y est autorisé
EXPOSE 8890/udp 8891/udp 9997/tcp
ENTRYPOINT ["/entrypoint.sh"]
