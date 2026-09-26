"""Lance des bancs de transport en parallèle dans WSL contre la réplique locale, et compare à l'optimum théorique.

  python3 bench/runner.py --scenarios bench/scenarios/gen --parallel 8 --out ~/turboirl-bench/results/run1
  python3 bench/runner.py --generate 40 --seed 100 --parallel 8 ...     # génère puis lance

Pour chaque scénario : simulateur de téléphone (phonesim) + sonde (tsprobe) sur un chemin MediaMTX propre, résultats
JSON, puis une ligne de tableau : mesuré / optimum pour le son, l'image, le débit vidéo, le surcoût de données.
Score (plus petit = mieux), dans l'ordre des priorités : trous de son ×100, trous d'image ×10, débit vidéo perdu
en % ×1, surcoût de données en % ×0,3.
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import oracle  # noqa: E402


def token():
    for p in (os.path.expanduser("~/.turboirl-vps.env"), "/mnt/c/Users/Fonias/.turboirl-vps.env"):
        if os.path.exists(p):
            for line in open(p, encoding="utf-8"):
                if line.startswith("WRITE_TOKEN="):
                    return line.split("=", 1)[1].strip()
    sys.exit("jeton introuvable")


def run_one(path, slot, out_dir, tok, vps):
    sc = json.load(open(path, encoding="utf-8"))
    name = sc.get("name") or os.path.basename(path)[:-5]
    seconds = int(sc.get("duration", 120))
    port = 9100 + 2 * slot
    mpath = f"turboirl-bench{slot}"
    env = dict(os.environ, TURBOIRL_NAKSHARE="0", PYTHONIOENCODING="utf-8")
    sim_out = os.path.join(out_dir, name + ".sim.json")
    probe_out = os.path.join(out_dir, name + ".probe.json")
    with open(os.path.join(out_dir, name + ".sim.log"), "w") as slog, open(os.path.join(out_dir, name + ".probe.log"), "w") as plog:
        sim = subprocess.Popen([sys.executable, "-u", os.path.join(HERE, "phonesim.py"), "--scenario", path, "--vps", vps, "--path", mpath,
                                "--ports", str(port), "--seconds", str(seconds), "--out", sim_out, "--token", tok, "--quiet"],
                               stdout=slog, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
        time.sleep(3)
        probe = subprocess.Popen([sys.executable, "-u", os.path.join(ROOT, "tools", "tsprobe.py"), "--url",
                                  f"srt://{vps}:8890?streamid=read:{mpath}:reader:{tok}&latency=500",
                                  "--seconds", str(seconds - 24), "--warmup", "16", "--json", probe_out, "--quiet"],
                                 stdout=plog, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
        probe.wait()
        sim.wait()
    try:
        s = json.load(open(sim_out, encoding="utf-8"))
        p = json.load(open(probe_out, encoding="utf-8"))
    except Exception as e:
        return {"name": name, "error": str(e)}
    o = oracle.compute(sc)
    son, img = p.get("son", {}), p.get("image", {})
    row = {
        "name": name, "family": sc.get("family", ""), "seconds": seconds,
        "audio_gap_s": round(son.get("gap_ms", 0) / 1000, 1), "audio_gap_oracle_s": o["audio_gap_s"], "audio_max_gap_s": round(son.get("max_gap_ms", 0) / 1000, 1),
        "video_gap_s": round(img.get("gap_ms", 0) / 1000, 1), "video_gap_oracle_s": o["video_gap_s"],
        "video_kbps": s["video_kbps_avg"], "video_kbps_oracle": o["video_kbps_avg"],
        "overhead": s["overhead"], "overhead_oracle": o["ideal_overhead"],
        "retransmitted": s["retransmitted"], "replayed": s["replayed"], "switches": s["ladder_switches"],
        "degraded_s": s["seconds_by_state"].get("dégradée", 0), "held_s": s["seconds_by_state"].get("retenue", 0),
        "shares": "/".join(f"{l['share']:.2f}" for l in s["links"]), "received": p.get("received", False),
    }
    video_loss_pct = max(0.0, 100.0 * (o["video_kbps_avg"] - s["video_kbps_avg"]) / max(o["video_kbps_avg"], 1))
    over_pct = max(0.0, 100.0 * (s["overhead"] - o["ideal_overhead"]))
    row["score"] = round(100 * max(0, row["audio_gap_s"] - o["audio_gap_s"]) + 10 * max(0, row["video_gap_s"] - o["video_gap_s"])
                         + video_loss_pct + 0.3 * over_pct, 1)
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios", default=os.path.join(HERE, "scenarios", "gen"))
    ap.add_argument("--generate", type=int, default=0, help="générer N scénarios (avec --seed) avant de lancer")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--family", default="")
    ap.add_argument("--parallel", type=int, default=6)
    ap.add_argument("--vps", default="127.0.0.1")
    ap.add_argument("--out", default=os.path.expanduser("~/turboirl-bench/results/" + time.strftime("run-%Y%m%d-%H%M")))
    ap.add_argument("--only", default="", help="sous-chaîne du nom des scénarios à lancer")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    if a.generate:
        cmd = [sys.executable, os.path.join(HERE, "scenario.py"), "--seed", str(a.seed), "--count", str(a.generate), "--out", a.scenarios]
        if a.family:
            cmd += ["--family", a.family]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
    paths = sorted(os.path.join(a.scenarios, f) for f in os.listdir(a.scenarios) if f.endswith(".json") and a.only in f)
    tok = token()
    print(f"{len(paths)} scénarios, {a.parallel} en parallèle, résultats dans {a.out}", flush=True)
    rows = []
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=a.parallel) as ex:
        futures = {}
        for i, p in enumerate(paths):
            slot = (i % a.parallel) + 1
            # deux scénarios ne partagent jamais un créneau en même temps : on attend celui du créneau précédent
            futures[ex.submit(run_one, p, slot, a.out, tok, a.vps)] = p
            if (i + 1) % a.parallel == 0:
                for f in list(futures):
                    rows.append(f.result())
                    print(fmt(rows[-1]), flush=True)
                futures = {}
        for f in list(futures):
            rows.append(f.result())
            print(fmt(rows[-1]), flush=True)
    rows.sort(key=lambda r: -r.get("score", 0))
    with open(os.path.join(a.out, "resume.csv"), "w", newline="", encoding="utf-8") as f:
        keys = sorted({k for r in rows for k in r})
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)
    ok = [r for r in rows if "error" not in r]
    print(f"\n{len(ok)} bancs en {(time.time() - t0) / 60:.1f} min ; score total {sum(r['score'] for r in ok):.0f} ; "
          f"son : {sum(r['audio_gap_s'] for r in ok):.1f} s manquants (optimum {sum(r['audio_gap_oracle_s'] for r in ok)}) ; "
          f"image : {sum(r['video_gap_s'] for r in ok):.1f} s (optimum {sum(r['video_gap_oracle_s'] for r in ok)}) ; "
          f"vidéo {sum(r['video_kbps'] for r in ok) / max(len(ok), 1):.0f} kb/s (optimum {sum(r['video_kbps_oracle'] for r in ok) / max(len(ok), 1):.0f})")
    print("pires :", ", ".join(f"{r['name']} ({r['score']})" for r in rows[:5] if "error" not in r))


def fmt(r):
    if "error" in r:
        return f"{r['name']:26s} ERREUR {r['error']}"
    return (f"{r['name']:26s} son {r['audio_gap_s']:5.1f}/{r['audio_gap_oracle_s']:<3d} img {r['video_gap_s']:5.1f}/{r['video_gap_oracle_s']:<3d} "
            f"vidéo {r['video_kbps']:4d}/{r['video_kbps_oracle']:<4d} kb/s surcoût ×{r['overhead']:.2f}/{r['overhead_oracle']:.2f} "
            f"dégr {r['degraded_s']:4.0f}s ret {r['held_s']:4.0f}s parts {r['shares']} score {r['score']}")


if __name__ == "__main__":
    main()
