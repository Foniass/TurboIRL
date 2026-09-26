#!/bin/bash
# Un banc de transport dans WSL : simulateur de téléphone + sonde, contre la réplique locale.
#   run1.sh <scenario.json> <index> <secondes> <dossier résultats>
W="/mnt/c/Users/Fonias/Documents/Claude Code/TurboIRL"
SC="$1"; I="${2:-1}"; SEC="${3:-120}"; OUT="${4:-$HOME/turboirl-bench/results}"
T=$(grep '^WRITE_TOKEN=' /mnt/c/Users/Fonias/.turboirl-vps.env | cut -d= -f2 | tr -d '\r')
mkdir -p "$OUT"
NAME=$(basename "$SC" .json)
PORT=$((9100 + 2 * I))
PATHN="turboirl-bench$I"
export TURBOIRL_NAKSHARE=0
cd "$W"
python3 -u bench/phonesim.py --scenario "$SC" --path "$PATHN" --ports $PORT --seconds $SEC --out "$OUT/$NAME.sim.json" > "$OUT/$NAME.sim.log" 2>&1 &
SIM=$!
sleep 4
python3 -u tools/tsprobe.py --url "srt://127.0.0.1:8890?streamid=read:$PATHN:reader:$T&latency=500" --seconds $((SEC - 22)) --warmup 16 --json "$OUT/$NAME.probe.json" > "$OUT/$NAME.probe.log" 2>&1
wait $SIM
python3 - "$OUT/$NAME" <<'PY'
import json, sys
b = sys.argv[1]
try:
    sim = json.load(open(b + ".sim.json")); pr = json.load(open(b + ".probe.json"))
except Exception as e:
    print("résultat incomplet :", e); sys.exit(1)
son, img = pr.get("son", {}), pr.get("image", {})
print(f"{b.split('/')[-1]:28s} son sans {son.get('gap_ms', '?')} ms ({son.get('gaps', '?')} trous, max {son.get('max_gap_ms', '?')}) | image sans {img.get('gap_ms', '?')} ms ({img.get('gaps', '?')}) | "
      f"vidéo moy {sim['video_kbps_avg']} kb/s min {sim['video_kbps_min']} | états {sim['seconds_by_state']} | surcoût ×{sim['overhead']} retrans {sim['retransmitted']} | parts {[l['share'] for l in sim['links']]}")
PY
