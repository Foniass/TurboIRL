#!/bin/bash
# Fumée : l'ancien banc (bondclient + ffmpeg SRT) contre la réplique WSL, mesuré par la sonde de transport.
W="/mnt/c/Users/Fonias/Documents/Claude Code/TurboIRL"
T=$(grep '^WRITE_TOKEN=' /mnt/c/Users/Fonias/.turboirl-vps.env | cut -d= -f2 | tr -d '\r')
export TURBOIRL_NAKSHARE=0
cd "$W"
python3 tools/bondclient.py --vps 127.0.0.1 --link "${1:-5G,share=0.6,delay=60}" --link "${2:-wifi,share=0.4,delay=20}" > ~/turboirl-bench/logs/smoke-bondclient.log 2>&1 &
BC=$!
sleep 1
ffmpeg -hide_banner -loglevel error -re -stream_loop -1 -i bench/turboirl-bench.mp4 -c copy -f mpegts \
  "srt://127.0.0.1:9040?streamid=publish:turboirl:phone:$T&latency=12000&pkt_size=1128" >/dev/null 2>&1 &
FF=$!
sleep 3
python3 tools/tsprobe.py --url "srt://127.0.0.1:8890?streamid=read:turboirl:reader:$T&latency=500" --seconds ${3:-40} --warmup 15
kill $FF $BC 2>/dev/null; sleep 1
tail -1 ~/turboirl-bench/logs/smoke-bondclient.log | cut -c1-260
