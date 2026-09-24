"""Lecture des sessions TurboIRL stockées sur le VPS (turboirl-api), pour analyser sans passer par Discord.

Le jeton de lecture vient de ~/.turboirl-vps.env (ligne READ_TOKEN=…, copie du /etc/turboirl/.env du VPS) ou de
la variable d'environnement TURBOIRL_READ_TOKEN.

Usage :
  python tools/vps.py sessions [N]                 liste des N dernières sessions (20 par défaut)
  python tools/vps.py get <session> [dossier]      télécharge telemetry.csv et journal.txt dans dossier (défaut : dumps/vps/<session>)
  python tools/vps.py range <de ISO> <à ISO> [f]   télémétrie de toutes les sessions de la période → f (défaut : stdout)
  python tools/vps.py health
  python tools/vps.py release                      versions publiées : canal test (Claude) et canal stable (l'ami)
  python tools/vps.py promote <version>            rend cette version stable (appli et logiciel PC) : le téléphone et le
                                                   PC de l'ami se mettent à jour dessus ; promote --app X ou --pc X pour un seul
  python tools/vps.py receiver [fichier]           journaux du logiciel PC (liste, ou contenu d'un fichier)
"""
import json
import os
import sys
import urllib.request

BASE = os.environ.get("TURBOIRL_API", "https://turboirl.mathisjacqueline.com")


def env_token(key):
    p = os.path.join(os.path.expanduser("~"), ".turboirl-vps.env")
    try:
        for line in open(p, encoding="utf-8"):
            if line.startswith(key + "="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def post(path, body):
    t = env_token("WRITE_TOKEN")
    if not t:
        sys.exit("WRITE_TOKEN absent de ~/.turboirl-vps.env")
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST")
    req.add_header("Authorization", "Bearer " + t)
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read().decode("utf-8")


def token():
    t = os.environ.get("TURBOIRL_READ_TOKEN")
    if t:
        return t
    p = os.path.join(os.path.expanduser("~"), ".turboirl-vps.env")
    try:
        for line in open(p, encoding="utf-8"):
            if line.startswith("READ_TOKEN="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    sys.exit(f"jeton de lecture introuvable ({p} ou TURBOIRL_READ_TOKEN)")


def get(path, auth=True):
    req = urllib.request.Request(BASE + path)
    if auth:
        req.add_header("Authorization", "Bearer " + token())
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read().decode("utf-8")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "sessions"
    if cmd == "health":
        print(get("/api/turboirl/health", auth=False))
    elif cmd == "sessions":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 20
        for s in json.loads(get(f"/api/turboirl/sessions?limit={n}")):
            j = f"journal ({s['journalReason']})" if s.get("hasJournal") else "sans journal"
            print(f"{s['session']:<40} {s.get('version', ''):>5}  début {s.get('startedAt', '?')}  dernier envoi {s.get('lastSeenAt', '?')}  {s.get('rows', 0):>5} lignes  {j}")
    elif cmd == "get":
        sid = sys.argv[2]
        out = sys.argv[3] if len(sys.argv) > 3 else os.path.join("dumps", "vps", sid)
        os.makedirs(out, exist_ok=True)
        for name in ("telemetry.csv", "journal.txt"):
            try:
                data = get(f"/api/turboirl/sessions/{sid}/{name}")
            except urllib.error.HTTPError as e:
                print(f"{name} : {e.code}")
                continue
            p = os.path.join(out, name)
            open(p, "w", encoding="utf-8", newline="").write(data)
            print(f"{p} ({len(data)} o)")
    elif cmd == "range":
        data = get(f"/api/turboirl/telemetry.csv?from={sys.argv[2]}&to={sys.argv[3]}")
        if len(sys.argv) > 4:
            open(sys.argv[4], "w", encoding="utf-8", newline="").write(data)
            print(f"{sys.argv[4]} ({data.count(chr(10))} lignes)")
        else:
            sys.stdout.write(data)
    elif cmd == "release":
        r = json.loads(get("/api/turboirl/release"))
        for ch in ("stable", "test"):
            print(f"{ch:7s} appli {r[ch]['app'] or '-':6s} PC {r[ch]['pc'] or '-'}")
        print("fichiers :", ", ".join(r.get("files", [])) or "-")
    elif cmd == "promote":
        args = sys.argv[2:]
        body = {"channel": "stable"}
        if args and not args[0].startswith("--"):
            body["app"] = body["pc"] = args[0]
        for i, a in enumerate(args):
            if a in ("--app", "--pc") and i + 1 < len(args):
                body[a[2:]] = args[i + 1]
        if "app" not in body and "pc" not in body:
            sys.exit("usage : promote <version> | promote --app X | promote --pc X")
        files = json.loads(get("/api/turboirl/release")).get("files", [])
        for k, name in (("app", f"TurboIRL-{body.get('app')}.apk"), ("pc", f"TurboIRL-PC-{body.get('pc')}.zip")):
            if k in body and name not in files:
                sys.exit(f"{name} n'est pas sur le VPS (publier d'abord avec tools/publish.py)")
        print("stable :", post("/api/turboirl/release", body))
    elif cmd == "receiver":
        if len(sys.argv) > 2:
            sys.stdout.write(get(f"/api/turboirl/receiver/{sys.argv[2]}"))
        else:
            for f in json.loads(get("/api/turboirl/receiver")):
                print(f)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
