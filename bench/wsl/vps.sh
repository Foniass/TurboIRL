#!/bin/bash
# Démarre / arrête / interroge la réplique du VPS dans WSL :  vps.sh start | stop | status
ROOT=~/turboirl-bench
case "$1" in
  start)
    "$0" stop >/dev/null 2>&1
    cd "$ROOT/mediamtx" && nohup ./mediamtx mediamtx.yml > "$ROOT/logs/mediamtx.log" 2>&1 &
    sleep 1
    cd "$ROOT/bond" && BOND_DEBUG=${BOND_DEBUG:-0} nohup python3 -u bond.py --port 8891 --srt 127.0.0.1:8890 > "$ROOT/logs/bond.log" 2>&1 &
    sleep 1
    "$0" status
    ;;
  stop)
    pkill -f "mediamtx mediamtx.yml" 2>/dev/null
    pkill -f "bond.py --port 8891" 2>/dev/null
    echo "réplique arrêtée"
    ;;
  status)
    pgrep -f "mediamtx mediamtx.yml" >/dev/null && echo "mediamtx : actif (8890 SRT, 9997 API)" || echo "mediamtx : arrêté"
    pgrep -f "bond.py --port 8891" >/dev/null && echo "bond : actif (8891 UDP)" || echo "bond : arrêté"
    hostname -I | awk '{print "adresse WSL vue de Windows :", $1}'
    ;;
  *) echo "usage : vps.sh start|stop|status" ;;
esac
