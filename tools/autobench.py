"""Boucle d'observation automatique : dès qu'un téléphone publie sur le relais du VPS, mesure ce que le viewer voit
(bench.py sur la sortie du récepteur local, port 9005) jusqu'à la fin du flux, puis affiche le bilan et le bilan des
liens du téléphone (télémétrie de la session). Une ligne par événement, pour laisser Claude suivre sans rien demander.

Le récepteur doit tourner :  python tools/receiver.py --no-vps --obs-overlay "" --obs-port 9005
  python tools/autobench.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://turboirl.mathisjacqueline.com"


def token():
    for line in open(os.path.join(os.path.expanduser("~"), ".turboirl-vps.env"), encoding="utf-8"):
        if line.startswith("READ_TOKEN="):
            return line.split("=", 1)[1].strip()
    sys.exit("READ_TOKEN absent")


TOKEN = token()


def get(path):
    req = urllib.request.Request(API + path)
    req.add_header("Authorization", "Bearer " + TOKEN)
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.read().decode("utf-8")


def relay_ready():
    try:
        return bool(json.loads(get("/api/turboirl/obs")).get("relay", {}).get("ready"))
    except Exception:
        return False


def say(msg):
    print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


def link_summary(session):
    """Dernière ligne de télémétrie et cumul par lien de la session."""
    try:
        rows = get(f"/api/turboirl/sessions/{session}/telemetry.csv").splitlines()
    except Exception as e:
        return f"télémétrie indisponible ({e})"
    if len(rows) < 2:
        return "télémétrie vide"
    hdr = rows[0].split(",")
    idx = {k: i for i, k in enumerate(hdr)}
    last = rows[-1].split(",")
    def col(r, k): return r[idx[k]] if k in idx and idx[k] < len(r) else ""
    n = len(rows) - 1
    susp = sum(1 for r in rows[1:] if col(r.split(","), "lien_5g_etat") == "suspect")
    suspw = sum(1 for r in rows[1:] if col(r.split(","), "lien_wifi_etat") == "suspect")
    return (f"{n} s de télémétrie ; 5G {col(last, 'lien_5g_etat')} {col(last, 'lien_5g_pct')} % (suspect {susp} s), "
            f"Wi-Fi {col(last, 'lien_wifi_etat')} {col(last, 'lien_wifi_pct')} % (suspect {suspw} s) ; "
            f"tampon SRT max {max((int(col(r.split(','), 'tampon_ms') or 0) for r in rows[1:]), default=0)} ms")


def main():
    say("observation automatique : en attente d'un flux sur le relais")
    while True:
        while not relay_ready():
            time.sleep(5)
        try:
            session = json.loads(get("/api/turboirl/sessions?limit=1"))[0]["session"]
        except Exception:
            session = "?"
        say(f"flux détecté sur le relais (session {session}) : mesure en cours…")
        p = subprocess.Popen([sys.executable, os.path.join(ROOT, "tools", "bench.py")],
                             stdout=subprocess.PIPE, text=True, encoding="utf-8", env=dict(os.environ, PYTHONIOENCODING="utf-8"))
        windows = []
        while True:
            line = p.stdout.readline()
            if not line:
                break
            line = line.strip()
            if line.startswith("[") and "10 s" in line:
                windows.append(line)
                if "doublons 0, sauts 0 (manquantes 0), désordre 0, illisibles 0 | son : trous 0 (0 ms), ruptures 0" not in line:
                    say("anomalie " + line)
            if not relay_ready():
                p.terminate()
                break
        try:
            out = p.communicate(timeout=10)[0]
            for l in out.splitlines():
                if l.startswith("BILAN"):
                    say(l)
        except Exception:
            p.kill()
        say(f"fin du flux : {len(windows)} tranches de 10 s mesurées ; liens : {link_summary(session)}")
        time.sleep(10)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
