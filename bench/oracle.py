"""Optimum théorique d'un scénario : ce qu'un système parfait, qui connaîtrait l'avenir des liens, aurait obtenu.

Hypothèses (seconde par seconde) :
  - un lien porte à l'instant t s'il n'est pas coupé et si sa latence + sa file ne dépassent pas le tampon ;
  - le son (≈ 80 kb/s avec l'enrobage) part sur tous les liens : il manque seulement si aucun lien ne porte pendant
    toute la fenêtre [t, t + tampon − 1 s] ;
  - la vidéo : le débit maximal b(t) tel que tout ce qui est émis arrive avant son échéance, la capacité totale des
    liens qui portent étant C(t) : b(t) = min sur k ≤ tampon de (moyenne de C sur [t, t+k[) − son, borné par le
    plafond de l'appli ; sous 150 kb/s, l'image est considérée absente ;
  - surcoût idéal : les pertes aléatoires (chaque paquet perdu est renvoyé une fois), rien d'autre.
Ce n'est pas exact au paquet près, c'est une borne raisonnable qui donne un écart comparable d'un scénario à l'autre.
"""
import json
import sys

AUDIO_KBPS = 84      # 64 kb/s AAC + enrobage TS
VIDEO_MIN_KBPS = 150


def link_at(link, t):
    """(porte ?, capacité kb/s, pertes) du lien à l'instant t."""
    for a, d in link.get("outages", []):
        if a <= t < a + d:
            return False, 0.0, 0.0, link["segments"][0]
    seg = link["segments"][0]
    for s in link["segments"]:
        if s["t"] <= t:
            seg = s
    kbps = seg.get("kbps", 0) or 1e9
    return True, kbps, seg.get("loss", 0.0), seg


def compute(sc):
    L = sc["phone"]["latency_ms"] / 1000.0
    max_kbps = sc["phone"].get("max_kbps", 3500)
    D = int(sc.get("duration", 120))
    win = max(1, int(L) - 1)
    carries, cap, loss = [], [], []
    for t in range(D):
        c_tot, up_any, l_best = 0.0, False, 1.0
        for link in sc["links"]:
            up, kbps, lo, seg = link_at(link, t)
            # une file qui gonfle au-delà du tampon ne porte plus dans les temps
            if up and (seg.get("delay", 0) + seg.get("queue", 0)) / 1000.0 > L - 1:
                up = False
            if up:
                up_any = True
                c_tot += kbps
                l_best = min(l_best, lo)
        carries.append(up_any)
        cap.append(c_tot)
        loss.append(l_best if up_any else 1.0)
    audio_gap = 0
    for t in range(D):
        if not any(carries[t:t + win]):
            audio_gap += 1
    video, video_gap = [], 0
    for t in range(D):
        best = max_kbps
        for k in range(1, int(L) + 1):
            if t + k > D:
                break
            avg = sum(cap[t:t + k]) / k
            best = min(best, avg - AUDIO_KBPS)
        b = max(0.0, min(best, max_kbps))
        if b < VIDEO_MIN_KBPS:
            video_gap += 1
            b = 0.0
        video.append(b)
    ideal_overhead = 1.0 + sum(l for l, c in zip(loss, carries) if c) / max(1, sum(carries))
    return {"audio_gap_s": audio_gap, "video_gap_s": video_gap, "video_kbps_avg": round(sum(video) / D),
            "ideal_overhead": round(ideal_overhead, 3), "seconds_no_link": D - sum(carries)}


if __name__ == "__main__":
    for p in sys.argv[1:]:
        sc = json.load(open(p, encoding="utf-8"))
        print(sc.get("name", p), compute(sc))
