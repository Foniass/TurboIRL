"""Publication d'une version sur le VPS (canal « test ») : dépose l'APK et/ou le zip PC dans /opt/turboirl/releases
par scp, puis annonce la version. Fonias la promeut ensuite en « stable » avec `python tools/vps.py promote`.

Usage :
  python tools/publish.py --app 2.4          dépose dist/TurboIRL-2.4.apk, canal test → appli 2.4
  python tools/publish.py --pc 2.4           dépose dist/TurboIRL-PC-2.4.zip, canal test → PC 2.4
  python tools/publish.py --app 2.4 --pc 2.4
Nécessite l'accès SSH debian@VPS (clé) et le jeton d'écriture dans ~/.turboirl-vps.env (WRITE_TOKEN).
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request

VPS = "debian@135.125.103.60"
RELEASES = "/opt/turboirl/data/releases"
API = "https://turboirl.mathisjacqueline.com"


def write_token():
    for line in open(os.path.join(os.path.expanduser("~"), ".turboirl-vps.env"), encoding="utf-8"):
        if line.startswith("WRITE_TOKEN="):
            return line.split("=", 1)[1].strip()
    sys.exit("WRITE_TOKEN absent de ~/.turboirl-vps.env")


def upload(path):
    if not os.path.isfile(path):
        sys.exit(f"fichier absent : {path}")
    name = os.path.basename(path)
    subprocess.run(["scp", "-o", "BatchMode=yes", path, f"{VPS}:/tmp/{name}"], check=True)
    subprocess.run(["ssh", "-o", "BatchMode=yes", VPS,
                    f"sudo mv /tmp/{name} {RELEASES}/{name} && sudo chown turboirl:turboirl {RELEASES}/{name} && sudo chmod 644 {RELEASES}/{name} && ls -la {RELEASES}/{name}"],
                   check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", help="version de l'APK (dist/TurboIRL-<v>.apk)")
    ap.add_argument("--pc", help="version du logiciel PC (dist/TurboIRL-PC-<v>.zip)")
    a = ap.parse_args()
    if not a.app and not a.pc:
        ap.error("--app et/ou --pc")
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    body = {"channel": "test"}
    if a.app:
        upload(os.path.join(root, "dist", f"TurboIRL-{a.app}.apk"))
        body["app"] = a.app
    if a.pc:
        upload(os.path.join(root, "dist", f"TurboIRL-PC-{a.pc}.zip"))
        body["pc"] = a.pc
    req = urllib.request.Request(API + "/api/turboirl/release", data=json.dumps(body).encode(), method="POST")
    req.add_header("Authorization", "Bearer " + write_token())
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as r:
        print("canal test :", r.read().decode())


if __name__ == "__main__":
    main()
