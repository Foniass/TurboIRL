"""Veille des sessions du téléphone sur le VPS : une ligne quand une session commence, quand son journal arrive
(auto / fin) avec les lignes qui comptent (décodeur, source de test, liens, bilan, erreurs), et quand elle se termine.
Sert à suivre les cycles de test sans que personne n'ait à prévenir.

  python tools/watchsessions.py
"""
import json
import os
import sys
import time
import urllib.request

API = "https://turboirl.mathisjacqueline.com"
KEYS = ("Décodeur", "décodeur", "Source de test", "Répartiteur", "Lien ", "Bilan liens", "Erreur", "SRT connecté", "SRT :",
        "Réencodage démarré", "impossible", "muet", "Mise à jour", "Process démarré")


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


def say(msg):
    try:
        print(time.strftime("[%H:%M:%S] ") + msg, flush=True)
    except Exception:
        print(time.strftime("[%H:%M:%S] ") + msg.encode("ascii", "replace").decode(), flush=True)


def main():
    seen_session = None
    seen_lines = set()
    last_reason = None
    say("veille des sessions : démarrée")
    while True:
        try:
            s = json.loads(get("/api/turboirl/sessions?limit=1"))[0]
            sid, reason = s["session"], s.get("journalReason") or s.get("reason") or ""
            if sid != seen_session:
                seen_session = sid
                seen_lines = set()
                last_reason = None
                say(f"nouvelle session {sid} (appli {s.get('version', '?')})")
            if reason != last_reason or reason in ("auto", "fin", "demarrage"):
                text = get(f"/api/turboirl/sessions/{sid}/journal.txt")
                start_marker = sid.split("-", 1)[1][:8]  # yyyymmdd
                new = []
                for line in text.splitlines():
                    if line in seen_lines or not any(k in line for k in KEYS):
                        continue
                    seen_lines.add(line)
                    new.append(line[6:])   # sans « 25/09 »
                if new:
                    say(f"journal ({reason}) : " + " | ".join(new[-12:]))
                if reason == "fin" and last_reason != "fin":
                    say(f"session {sid} terminée")
                last_reason = reason
        except Exception as e:
            try:
                say(f"veille : erreur passagère ({e!r})")
            except Exception:
                pass
        time.sleep(30)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
