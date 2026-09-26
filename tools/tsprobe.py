"""Sonde de transport : lit le flux du relais (SRT) sans le décoder et mesure ce qui manque, à partir des PTS.

Chaque paquet audio AAC dure 21,3 ms (1024 échantillons à 48 kHz), chaque image 33,3 ms : un saut de PTS plus grand
que ça sur une piste est un trou que le viewer entendrait ou verrait. C'est la métrique du banc de transport, calculable
par dizaines en parallèle sans encodeur ni décodeur (les bancs vidéo complets restent la couche de confirmation).

  python tools/tsprobe.py --url "srt://127.0.0.1:8890?streamid=read:turboirl-bench1:reader:<jeton>&latency=500" --seconds 90
Sortie : une ligne par 10 s (trous son / image en ms, images, octets) et un BILAN JSON en fin.
"""
import argparse
import json
import os
import subprocess
import sys
import time

TS = 188
AUDIO_FRAME_S = 1024 / 48000.0


def parse_args(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True, help="entrée ffmpeg (srt://… ou udp://…)")
    ap.add_argument("--seconds", type=float, default=90, help="durée de mesure après le premier paquet")
    ap.add_argument("--warmup", type=float, default=0, help="secondes ignorées après le premier paquet")
    ap.add_argument("--json", default="", help="fichier où écrire le bilan JSON")
    ap.add_argument("--retry-s", type=float, default=40, help="réessaie la connexion pendant N s si le flux n'est pas encore là")
    ap.add_argument("--quiet", action="store_true")
    return ap.parse_args(argv)


def pts_of(payload):
    """PTS (secondes) d'un début de PES, ou None."""
    if len(payload) < 14 or payload[0:3] != b"\x00\x00\x01":
        return None
    flags = payload[7]
    if not flags & 0x80:
        return None
    p = payload[9:14]
    pts = ((p[0] >> 1) & 0x07) << 30 | p[1] << 22 | (p[2] >> 1) << 15 | p[3] << 7 | p[4] >> 1
    return pts / 90000.0


class Track:
    def __init__(self, name, frame_s, tolerance_s):
        self.name = name
        self.frame_s = frame_s
        self.tol = tolerance_s
        self.last_pts = None
        self.units = 0
        self.gap_s = 0.0
        self.gaps = 0
        self.max_gap_s = 0.0
        self.window_gap_s = 0.0

    def feed(self, pts):
        self.units += 1
        if self.last_pts is not None:
            d = pts - self.last_pts
            if d > self.frame_s + self.tol:
                missing = d - self.frame_s
                self.gap_s += missing
                self.window_gap_s += missing
                self.gaps += 1
                self.max_gap_s = max(self.max_gap_s, missing)
            elif d < -1.0:
                # rembobinage (relance de la source) : on repart sans compter
                pass
        self.last_pts = pts


def main(argv=None):
    a = parse_args(argv)
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-i", a.url, "-map", "0", "-c", "copy",
           "-f", "mpegts", "-muxdelay", "0", "-"]
    # le flux peut ne pas être encore publié : on relance ffmpeg jusqu'au premier octet (ou l'échéance)
    deadline = time.time() + a.retry_s
    while True:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        first = proc.stdout.read(TS)
        if first:
            break
        proc.wait()
        if time.time() > deadline:
            print("BILAN " + json.dumps({"seconds": 0.0, "bytes": 0, "kbps": 0, "received": False}), flush=True)
            return None
        time.sleep(1.0)
    tracks = {}          # pid → Track
    pmt_pids = set()
    stream_types = {}    # pid → type
    t_first = None
    t_report = None
    total_bytes = 0
    buf = first
    started = time.time()
    result = None
    try:
        while True:
            chunk = proc.stdout.read(TS * 64)
            if not chunk:
                break
            now = time.time()
            if t_first is None:
                t_first = now
                t_report = now
            buf += chunk
            n = len(buf) // TS * TS
            data, buf = buf[:n], buf[n:]
            if now - t_first < a.warmup:
                continue
            total_bytes += len(data)
            for i in range(0, len(data), TS):
                pkt = data[i:i + TS]
                if pkt[0] != 0x47:
                    continue
                pid = (pkt[1] & 0x1F) << 8 | pkt[2]
                pusi = pkt[1] & 0x40
                afc = (pkt[3] >> 4) & 0x3
                off = 4
                if afc & 0x2:
                    off += 1 + pkt[4]
                if not afc & 0x1 or off >= TS:
                    continue
                payload = pkt[off:]
                if pid == 0:  # PAT
                    p = payload[1 + payload[0]:] if pusi else payload
                    sec_len = (p[1] & 0x0F) << 8 | p[2]
                    for j in range(8, min(3 + sec_len - 4, len(p) - 3), 4):
                        prog = p[j] << 8 | p[j + 1]
                        if prog != 0:
                            pmt_pids.add((p[j + 2] & 0x1F) << 8 | p[j + 3])
                    continue
                if pid in pmt_pids:
                    p = payload[1 + payload[0]:] if pusi else payload
                    sec_len = (p[1] & 0x0F) << 8 | p[2]
                    info_len = (p[10] & 0x0F) << 8 | p[11]
                    j = 12 + info_len
                    end = 3 + sec_len - 4
                    while j + 5 <= min(end, len(p)):
                        st = p[j]
                        epid = (p[j + 1] & 0x1F) << 8 | p[j + 2]
                        es_len = (p[j + 3] & 0x0F) << 8 | p[j + 4]
                        if epid not in tracks:
                            if st in (0x0F, 0x11, 0x03, 0x04):
                                tracks[epid] = Track("son", AUDIO_FRAME_S, 0.012)
                            elif st in (0x24, 0x1B, 0x02):
                                tracks[epid] = Track("image", 1 / 30.0, 0.020)
                            stream_types[epid] = st
                        j += 5 + es_len
                    continue
                tr = tracks.get(pid)
                if tr is not None and pusi:
                    pts = pts_of(payload)
                    if pts is not None:
                        tr.feed(pts)
            if now - t_report >= 10.0:
                t_report = now
                if not a.quiet:
                    parts = ", ".join(f"trous {t.name} {t.window_gap_s * 1000:.0f} ms" for t in tracks.values())
                    print(f"[{time.strftime('%H:%M:%S')}] 10 s : {parts}, {total_bytes / 1e6:.1f} Mo", flush=True)
                for t in tracks.values():
                    t.window_gap_s = 0.0
            if now - t_first >= a.warmup + a.seconds:
                break
    finally:
        proc.kill()
    measured = (time.time() - t_first - a.warmup) if t_first else 0.0
    result = {"seconds": round(measured, 1), "bytes": total_bytes, "kbps": round(total_bytes * 8 / max(measured, 1e-6) / 1000),
              "received": t_first is not None}
    for t in tracks.values():
        result[t.name] = {"units": t.units, "gap_ms": round(t.gap_s * 1000), "gaps": t.gaps, "max_gap_ms": round(t.max_gap_s * 1000)}
    print("BILAN " + json.dumps(result, ensure_ascii=False), flush=True)
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
    return result


if __name__ == "__main__":
    main()
