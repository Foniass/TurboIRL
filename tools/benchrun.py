"""Lance un scénario complet du banc d'essai sur le PC : client bond avec liens simulés, ffmpeg qui publie la vidéo
synthétique en boucle, mesure de ce que le viewer voit (bench.py). Le récepteur doit déjà tourner sur le port 9005 :
  python tools/receiver.py --no-vps --obs-overlay "" --obs-port 9005

  python tools/benchrun.py --seconds 90 --link "5G,share=0.6,outage=40/8" --link "wifi,share=0.4"
"""
import argparse
import os
import signal
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def token():
    for line in open(os.path.join(os.path.expanduser("~"), ".turboirl-vps.env"), encoding="utf-8"):
        if line.startswith("WRITE_TOKEN="):
            return line.split("=", 1)[1].strip()
    sys.exit("WRITE_TOKEN absent")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--link", action="append", required=True)
    ap.add_argument("--seconds", type=int, default=90)
    ap.add_argument("--warmup", type=int, default=25, help="secondes avant de mesurer (connexion, 12 s de latence)")
    ap.add_argument("--latency", type=int, default=12000)
    a = ap.parse_args()
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    bond_log = open(os.path.join(ROOT, "dumps", "bench-bond.log"), "w", encoding="utf-8")
    bond = subprocess.Popen([sys.executable, os.path.join(ROOT, "tools", "bondclient.py")] + sum((["--link", l] for l in a.link), []),
                            stdout=bond_log, stderr=subprocess.STDOUT, env=env)
    time.sleep(1)
    ff = subprocess.Popen(["ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-stream_loop", "-1",
                           "-i", os.path.join(ROOT, "bench", "turboirl-bench.mp4"), "-c", "copy", "-f", "mpegts",
                           f"srt://127.0.0.1:9040?streamid=publish:turboirl:phone:{token()}&latency={a.latency}&pkt_size=1128"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"scénario : {' | '.join(a.link)} ; mesure de {a.seconds} s après {a.warmup} s de mise en route", flush=True)
    try:
        time.sleep(a.warmup)
        bench = subprocess.run([sys.executable, os.path.join(ROOT, "tools", "bench.py"), "--seconds", str(a.seconds)],
                               env=env, capture_output=True, text=True, encoding="utf-8")
        for line in bench.stdout.splitlines():
            if line.startswith("[") or line.startswith("BILAN"):
                print(line, flush=True)
    finally:
        for p in (ff, bond):
            p.kill()
        bond_log.close()
    lines = open(os.path.join(ROOT, "dumps", "bench-bond.log"), encoding="utf-8").read().splitlines()
    print("liens (dernier relevé) :", lines[-1] if lines else "-", flush=True)


if __name__ == "__main__":
    main()
