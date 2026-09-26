"""Générateur de scénarios pour le banc de transport : deux liens (cellulaire du téléphone, Wi-Fi du second
téléphone) dont les défauts varient dans le temps, tirés de familles inspirées des sorties réelles… et de bien pire.

  python3 bench/scenario.py --seed 1 --count 20 --out bench/scenarios/gen
Chaque scénario est reproductible (graine dans le nom) : sNNNNN-<famille>.json.

Familles (poids) :
  calme        liens sains, latences et gigue réalistes
  4g_bloat     cellulaire qui met en file (bufferbloat) puis s'effondre par moments (sortie 4G chez lui)
  handover     micro-coupures cellulaires régulières (changement d'antenne en roulant)
  mort         un lien mort 10 à 120 s
  double_mort  les deux liens absents 3 à 40 s (au-delà du tampon parfois)
  pertes       pertes aléatoires et rafales
  flap         alternance rapide bon / mauvais
  wifi_mauvais partage de connexion pourri : 300 à 1200 ms, 5 à 40 % de pertes (session 14:20)
  extreme      tout à la fois, sans retenue
"""
import argparse
import json
import os
import random

FAMILIES = ["calme", "4g_bloat", "handover", "mort", "double_mort", "pertes", "flap", "wifi_mauvais", "extreme"]
WEIGHTS = [1, 3, 2, 1, 1, 2, 2, 1, 3]


def seg(t, kbps=0, delay=40, jitter=10, loss=0.0, queue=500, burst_p=0.0, burst_len=0.0):
    return {"t": round(t, 1), "kbps": int(kbps), "delay": int(delay), "jitter": int(jitter), "loss": round(loss, 4),
            "queue": int(queue), "burst_p": round(burst_p, 4), "burst_len": round(burst_len, 2)}


def good_cell(r, t):
    return seg(t, kbps=r.choice([0, 0, 6000, 12000, 25000]), delay=r.uniform(25, 80), jitter=r.uniform(5, 40), queue=r.uniform(300, 1500))


def good_wifi(r, t):
    return seg(t, kbps=r.choice([0, 4000, 8000, 15000]), delay=r.uniform(20, 120), jitter=r.uniform(10, 60), queue=r.uniform(300, 1500))


def timeline(r, duration, make_seg, n_min=3, n_max=12):
    """Segments à instants aléatoires ; make_seg(r, t, i) → segment."""
    n = r.randint(n_min, n_max)
    times = sorted({0.0} | {r.uniform(2, duration - 2) for _ in range(n - 1)})
    return [make_seg(r, t, i) for i, t in enumerate(times)]


def generate(seed):
    r = random.Random(seed)
    fam = r.choices(FAMILIES, WEIGHTS)[0]
    duration = r.choice([120, 150, 180, 240])
    phone = {"latency_ms": r.choice([13000, 13000, 13000, 8000, 10000, 16000, 20000]), "min_kbps": 400, "max_kbps": 3500, "audio_kbps": 64}
    cell = {"name": "5G", "share": 0.6}
    wifi = {"name": "wifi", "share": 0.4}
    cell["segments"], wifi["segments"] = [good_cell(r, 0)], [good_wifi(r, 0)]
    cell["outages"], wifi["outages"] = [], []

    def bloat_seg(r, t, i):
        if r.random() < 0.35:
            return seg(t, kbps=r.uniform(80, 500), delay=r.uniform(40, 200), jitter=r.uniform(20, 200), queue=r.uniform(3000, 25000))
        return seg(t, kbps=r.uniform(600, 3500), delay=r.uniform(30, 120), jitter=r.uniform(10, 80), queue=r.uniform(2000, 20000))

    def wifi_bad_seg(r, t, i):
        return seg(t, kbps=r.uniform(500, 6000), delay=r.uniform(300, 1200), jitter=r.uniform(100, 600), loss=r.uniform(0.05, 0.4), queue=r.uniform(500, 3000))

    def loss_seg(r, t, i):
        return seg(t, kbps=r.choice([0, 3000, 8000]), delay=r.uniform(20, 100), jitter=r.uniform(5, 60), loss=r.uniform(0.005, 0.15),
                   burst_p=r.uniform(0, 0.02), burst_len=r.uniform(0.05, 1.5))

    def flap_seg(r, t, i):
        good = i % 2 == 0
        return seg(t, kbps=0 if good else r.uniform(100, 800), delay=40 if good else r.uniform(200, 2000), jitter=10 if good else r.uniform(50, 500),
                   loss=0 if good else r.uniform(0, 0.2), queue=1000 if good else r.uniform(1000, 10000))

    def outages(r, every_lo, every_hi, dur_lo, dur_hi):
        out, t = [], r.uniform(3, every_hi)
        while t < duration - 2:
            d = r.uniform(dur_lo, dur_hi)
            out.append([round(t, 1), round(d, 1)])
            t += d + r.uniform(every_lo, every_hi)
        return out

    if fam == "4g_bloat":
        cell["segments"] = timeline(r, duration, bloat_seg, 4, 14)
    elif fam == "handover":
        cell["outages"] = outages(r, 8, 40, 0.3, 3)
        if r.random() < 0.5:
            wifi["outages"] = outages(r, 20, 60, 0.2, 1.5)
    elif fam == "mort":
        link = r.choice([cell, wifi])
        t = r.uniform(10, duration - 30)
        link["outages"] = [[round(t, 1), round(r.uniform(10, 120), 1)]]
    elif fam == "double_mort":
        t = r.uniform(15, duration - 45)
        d = r.uniform(3, 40)
        cell["outages"] = [[round(t, 1), round(d, 1)]]
        wifi["outages"] = [[round(t + r.uniform(-1, 1), 1), round(d + r.uniform(-2, 2), 1)]]
    elif fam == "pertes":
        cell["segments"] = timeline(r, duration, loss_seg, 2, 8)
        if r.random() < 0.5:
            wifi["segments"] = timeline(r, duration, loss_seg, 2, 6)
    elif fam == "flap":
        n = r.randint(6, 40)
        times = [0.0]
        while times[-1] < duration - 2:
            times.append(times[-1] + r.uniform(2, 10))
        cell["segments"] = [flap_seg(r, t, i) for i, t in enumerate(times[:-1])]
    elif fam == "wifi_mauvais":
        wifi["segments"] = timeline(r, duration, wifi_bad_seg, 2, 8)
    elif fam == "extreme":
        cell["segments"] = timeline(r, duration, lambda r, t, i: r.choice([bloat_seg, loss_seg, flap_seg])(r, t, i), 5, 20)
        wifi["segments"] = timeline(r, duration, lambda r, t, i: r.choice([wifi_bad_seg, loss_seg, lambda r, t, i: good_wifi(r, t)])(r, t, i), 3, 12)
        cell["outages"] = outages(r, 5, 60, 0.5, 25)
        wifi["outages"] = outages(r, 10, 90, 0.5, 15)
        for s in cell["segments"] + wifi["segments"]:
            if r.random() < 0.15:
                s["kbps"] = int(r.uniform(50, 200))
                s["queue"] = int(r.uniform(5000, 30000))
            if r.random() < 0.1:
                s["loss"] = round(r.uniform(0.3, 0.6), 3)
    return {"name": f"s{seed:05d}-{fam}", "family": fam, "seed": seed, "duration": duration, "phone": phone, "links": [cell, wifi]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--count", type=int, default=10)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "scenarios", "gen"))
    ap.add_argument("--family", default="", help="forcer une famille")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    made = []
    seed = a.seed
    while len(made) < a.count:
        sc = generate(seed)
        seed += 1
        if a.family and sc["family"] != a.family:
            continue
        path = os.path.join(a.out, sc["name"] + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(sc, f, ensure_ascii=False, indent=1)
        made.append(path)
    print("\n".join(made))


if __name__ == "__main__":
    main()
