"""Banc d'essai : mesure ce que le viewer verrait, à partir de la sortie du récepteur (udp://127.0.0.1:9005 par
défaut) quand la source est la vidéo synthétique bench/turboirl-bench.mp4 (numéro d'image codé en 11 blocs
noirs/blancs dans la bande du haut, son = 440 Hz continu).

Par tranche de 10 s : images lues, doublons (image figée), sauts (images manquantes), désordre, illisibles ;
son : trous (silence) et ruptures de phase (son sauté ou rafistolé). À la fin, un bilan.

  python tools/bench.py [--port 9005] [--seconds 120]
"""
import argparse
import math
import socket
import struct
import subprocess
import sys
import threading
import time

W, H, BAND = 1280, 48, 48          # bande du haut décodée seule (gris)
BITS = 11
TONE = 440.0
RATE = 48000


def bar(gray, x):
    """Moyenne des pixels d'un bloc 32×48 dont le bord gauche est x (on lit le centre 16×24)."""
    s = 0
    for y in range(12, 36):
        row = y * W
        s += sum(gray[row + x + 8: row + x + 24])
    return s / (24 * 16)


def read_frame_id(gray):
    if bar(gray, 0) < 128 or bar(gray, 520) < 128:
        return None
    n = 0
    for i in range(BITS):
        if bar(gray, 48 + i * 40) >= 128:
            n |= 1 << i
    return n


class Audio(threading.Thread):
    """Lit le PCM mono 48 kHz sur un socket TCP local : silences et ruptures de phase du 440 Hz."""

    def __init__(self, port):
        super().__init__(daemon=True)
        self.port = port
        self.gaps = 0
        self.gap_ms = 0.0
        self.phase_jumps = 0
        self.samples = 0
        self.lock = threading.Lock()

    def run(self):
        try:
            self._run()
        except Exception as e:  # visible dans le bilan plutôt qu'avalé
            print(f"son : lecture arrêtée ({e!r})", flush=True)

    def _run(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", self.port))
        srv.listen(1)
        conn, _ = srv.accept()
        block = RATE // 100  # 10 ms
        buf = b""
        in_gap = False
        gap_len = 0
        last_phase = None
        n_block = 0
        acc_re = acc_im = 0.0
        acc_n = 0
        while True:
            data = conn.recv(65536)
            if not data:
                break
            buf += data
            while len(buf) >= block * 2:
                chunk = buf[:block * 2]
                buf = buf[block * 2:]
                s = struct.unpack(f"<{block}h", chunk)
                rms = math.sqrt(sum(v * v for v in s) / block)
                t0 = self.samples
                self.samples += block
                if rms < 300:
                    gap_len += 1
                    if not in_gap:
                        in_gap = True
                    continue
                if in_gap:
                    with self.lock:
                        self.gaps += 1
                        self.gap_ms += gap_len * 10
                    in_gap = False
                    gap_len = 0
                    last_phase = None  # après un trou, la phase repart
                # phase du 440 Hz sur ce bloc (corrélation avec une référence synchrone sur l'échantillon absolu)
                re = im = 0.0
                for k, v in enumerate(s):
                    ang = 2 * math.pi * TONE * (t0 + k) / RATE
                    re += v * math.cos(ang)
                    im -= v * math.sin(ang)
                ph = math.atan2(im, re)
                if last_phase is not None:
                    d = abs((ph - last_phase + math.pi) % (2 * math.pi) - math.pi)
                    if d > math.radians(35):
                        with self.lock:
                            self.phase_jumps += 1
                last_phase = ph

    def snapshot(self):
        with self.lock:
            out = (self.gaps, self.gap_ms, self.phase_jumps)
            self.gaps, self.gap_ms, self.phase_jumps = 0, 0.0, 0
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9005)
    ap.add_argument("--audio-port", type=int, default=9051)
    ap.add_argument("--seconds", type=int, default=0, help="durée (0 = jusqu'à Ctrl+C)")
    a = ap.parse_args()
    audio = Audio(a.audio_port)
    audio.start()
    time.sleep(0.3)
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostats",
           # la sonde doit voir une image clé (toutes les 2 s) pour connaître la taille des images
           "-probesize", "20M", "-analyzeduration", "6M", "-i", f"udp://127.0.0.1:{a.port}?fifo_size=100000&overrun_nonfatal=1",
           "-map", "0:v:0", "-vf", f"crop={W}:{BAND}:0:0,format=gray", "-f", "rawvideo", "-",
           "-map", "0:a:0", "-ac", "1", "-ar", str(RATE), "-f", "s16le", f"tcp://127.0.0.1:{a.audio_port}"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
    frame_bytes = W * BAND
    prev = None
    win = dict(frames=0, dup=0, skip=0, missing=0, disorder=0, unreadable=0)
    total = dict(win)
    t_start = time.time()
    t_win = t_start
    print("banc : lecture de udp://127.0.0.1:%d" % a.port, flush=True)
    try:
        while True:
            # tube non tamponné : read(n) peut rendre moins de n octets, on complète
            parts = []
            got = 0
            while got < frame_bytes:
                chunk = p.stdout.read(frame_bytes - got)
                if not chunk:
                    break
                parts.append(chunk)
                got += len(chunk)
            if got < frame_bytes:
                break
            data = b"".join(parts)
            fid = read_frame_id(data)
            win["frames"] += 1
            if fid is None:
                win["unreadable"] += 1
            elif prev is not None:
                d = (fid - prev) % 2048
                if d == 0:
                    win["dup"] += 1
                elif d > 1 and d < 1024:
                    win["skip"] += 1
                    win["missing"] += d - 1
                elif d >= 1024:
                    win["disorder"] += 1
            if fid is not None:
                prev = fid
            now = time.time()
            if now - t_win >= 10:
                gaps, gap_ms, jumps = audio.snapshot()
                print(time.strftime("[%H:%M:%S] ") + f"10 s : images {win['frames']}, doublons {win['dup']}, sauts {win['skip']} "
                      f"(manquantes {win['missing']}), désordre {win['disorder']}, illisibles {win['unreadable']} | "
                      f"son : trous {gaps} ({gap_ms:.0f} ms), ruptures {jumps}", flush=True)
                for k in win:
                    total[k] += win[k]
                    win[k] = 0
                t_win = now
            if a.seconds and now - t_start >= a.seconds:
                break
    except KeyboardInterrupt:
        pass
    finally:
        p.kill()
    for k in win:
        total[k] += win[k]
    print(f"BILAN {time.time() - t_start:.0f} s : images {total['frames']}, doublons {total['dup']}, sauts {total['skip']} "
          f"(manquantes {total['missing']}), désordre {total['disorder']}, illisibles {total['unreadable']}", flush=True)


if __name__ == "__main__":
    main()
