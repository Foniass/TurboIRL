"""Rejoue un dump MPEG-TS brut vers UDP en temps réel, cadencé par les PTS audio.

Pourquoi pas « ffmpeg -re » : ffmpeg cadence sur les DTS de chaque paquet et s'endort quand la vidéo
saute (trou de 8 s pendant une zone morte) — il retient alors aussi le son, ce qui n'arrive pas en direct.
Pourquoi pas le PCR : il est porté par le PID vidéo et disparaît avec elle en mode critique.
Le son, lui, est continu par construction (c'est la priorité du relais) : rejouer sur les PTS des PES
audio reproduit l'arrivée des paquets telle que le PC l'a vue, y compris pendant les trous vidéo.

Usage : python tools/replay.py dumps/dump-....ts [--start S] [--duration S] [--port 9003]
"""
import argparse
import socket
import sys
import time

TS = 188


def pes_pts(pkt: bytes):
    """PTS (en secondes) si ce paquet ouvre un PES audio (stream_id 0xC0-0xDF) avec PTS, sinon None."""
    if pkt[0] != 0x47 or not (pkt[1] & 0x40):  # payload_unit_start_indicator
        return None
    afc = (pkt[3] >> 4) & 3
    if not (afc & 1):
        return None
    off = 4 + (1 + pkt[4] if afc & 2 else 0)
    if off + 14 > TS or pkt[off:off + 3] != b"\x00\x00\x01":
        return None
    if not 0xC0 <= pkt[off + 3] <= 0xDF or not (pkt[off + 7] & 0x80):
        return None
    p = pkt[off + 9:off + 14]
    pts = ((p[0] >> 1) & 7) << 30 | p[1] << 22 | (p[2] >> 1) << 15 | p[3] << 7 | p[4] >> 1
    return pts / 90_000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("--start", type=float, default=0.0, help="début (s depuis le premier PTS audio)")
    ap.add_argument("--duration", type=float, default=0.0, help="durée (s), 0 = jusqu'à la fin")
    ap.add_argument("--port", type=int, default=9003)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    dest = (a.host, a.port)
    end = a.start + a.duration if a.duration > 0 else float("inf")

    first = None          # premier PTS audio du dump
    last = None           # dernier PTS vu (détection de saut en arrière)
    offset = 0.0          # correction cumulée si le PTS recule (redémarrage du muxeur)
    t0 = None             # horloge murale correspondant au PTS `start`
    pending = bytearray() # paquets en attente jusqu'au prochain PTS audio
    sent = 0
    started = False

    def flush(when):
        """Envoie les paquets en attente à l'instant `when` (s de flux), par datagrammes de 7 paquets."""
        nonlocal sent, t0
        if t0 is None:
            t0 = time.monotonic() - (when - a.start)
        delay = t0 + (when - a.start) - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        for i in range(0, len(pending), 7 * TS):
            sock.sendto(bytes(pending[i:i + 7 * TS]), dest)
        sent += len(pending) // TS
        pending.clear()

    with open(a.dump, "rb") as f:
        while True:
            pkt = f.read(TS)
            if len(pkt) < TS:
                break
            pts = pes_pts(pkt)
            if pts is None:
                if started:
                    pending += pkt
                elif first is not None:
                    # avant `start` : on ne garde que PAT/PMT pour que ffmpeg démarre vite
                    pid = ((pkt[1] & 0x1F) << 8) | pkt[2]
                    if pid in (0, 0x1000):
                        pending += pkt
                        if len(pending) > 64 * TS:
                            del pending[: 32 * TS]
                continue
            if last is not None and pts < last - 1.0:
                offset += last - pts  # le PTS a reculé : on continue en temps monotone
            last = pts
            rel = pts + offset
            if first is None:
                first = rel
            rel -= first
            if rel > end:
                break
            if rel >= a.start:
                if not started:
                    started = True
                    print(f"Relecture depuis {rel:.1f} s de flux", flush=True)
                pending += pkt
                flush(rel)
    if pending and started:
        flush(last + offset - first)
    print(f"Terminé : {sent} paquets TS envoyés", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
