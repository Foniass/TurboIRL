#!/bin/sh
# Jetons pris dans l'environnement (les mêmes que ~/.turboirl-vps.env sur le PC, pour que les outils du banc
# parlent au conteneur comme au vrai VPS).
set -e
: "${WRITE_TOKEN:?WRITE_TOKEN requis}"
: "${READ_TOKEN:?READ_TOKEN requis}"
sed -e "s/__WRITE_TOKEN__/$WRITE_TOKEN/g" -e "s/__READ_TOKEN__/$READ_TOKEN/g" /opt/mediamtx/mediamtx.yml.template > /opt/mediamtx/mediamtx.yml
sysctl -w net.core.rmem_max=16777216 net.core.rmem_default=4194304 net.core.wmem_max=16777216 net.core.wmem_default=1048576 2>/dev/null || true
cd /opt/mediamtx && ./mediamtx mediamtx.yml &
MTX=$!
sleep 1
cd /opt/turboirl/bond && exec python3 -u bond.py --port 8891 --srt 127.0.0.1:8890 ${BOND_ARGS}
