#!/bin/bash
# Réplique locale du VPS dans WSL Ubuntu (sans sudo) : MediaMTX (même version que le VPS) + bond (même fichier que
# le dépôt turboirl-api). Tout vit dans ~/turboirl-bench. Lancer depuis Windows :
#   wsl -d Ubuntu -- bash "/mnt/c/Users/Fonias/Documents/Claude Code/TurboIRL/bench/wsl/setup.sh"
set -e
ROOT=~/turboirl-bench
WIN="/mnt/c/Users/Fonias/Documents/Claude Code"
MTX_VERSION=1.21.1
mkdir -p "$ROOT/mediamtx" "$ROOT/bond" "$ROOT/logs" "$ROOT/results"
if [ ! -x "$ROOT/mediamtx/mediamtx" ]; then
    curl -fsSL "https://github.com/bluenviron/mediamtx/releases/download/v${MTX_VERSION}/mediamtx_v${MTX_VERSION}_linux_amd64.tar.gz" \
        | tar -xz -C "$ROOT/mediamtx" mediamtx
fi
cp "$WIN/turboirl-api/bond/bond.py" "$ROOT/bond/bond.py"
WRITE_TOKEN=$(grep '^WRITE_TOKEN=' /mnt/c/Users/Fonias/.turboirl-vps.env | cut -d= -f2 | tr -d '\r')
READ_TOKEN=$(grep '^READ_TOKEN=' /mnt/c/Users/Fonias/.turboirl-vps.env | cut -d= -f2 | tr -d '\r')
sed -e "s/__WRITE_TOKEN__/$WRITE_TOKEN/g" -e "s/__READ_TOKEN__/$READ_TOKEN/g" \
    "$WIN/TurboIRL/bench/vps/mediamtx.yml.template" > "$ROOT/mediamtx/mediamtx.yml"
echo "réplique prête dans $ROOT (MediaMTX $("$ROOT/mediamtx/mediamtx" --version 2>&1 | head -1), bond $(md5sum "$ROOT/bond/bond.py" | cut -c1-8))"
