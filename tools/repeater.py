"""Répéteur temps réel entre le ffmpeg décodeur et le ffmpeg encodeur du récepteur.

Pourquoi : la source multimédia d'OBS retient le son tant qu'elle ne reçoit pas d'image plus récente.
Un trou vidéo de 8 s (zone morte, mode critique « seul le son part ») devenait 6 s de silence dans OBS
alors que le son arrivait en continu. ffmpeg ne peut pas dupliquer une image *avant* d'avoir reçu la
suivante ; ce script, lui, tourne sur l'horloge murale :
  - vidéo : reçoit les images brutes (yuv420p) du décodeur, renvoie la plus récente à cadence fixe,
    qu'une nouvelle image soit arrivée ou non (noir tant qu'aucune image n'a été reçue) ;
  - son : reçoit le PCM du décodeur dans une petite file d'attente, renvoie exactement 48 000 échantillons
    par seconde ; du silence quand la file est vide, saut en avant si elle dépasse le plafond.
Vidéo et son sortent du même tick d'horloge : ils restent alignés par construction.
Le décodeur peut se déconnecter/reconnecter (redémarrage du téléphone) : la sortie ne s'arrête jamais.

Ports (TCP, boucle locale) : entrées --in-video/--in-audio (le décodeur s'y connecte),
sorties --out-video/--out-audio (l'encodeur s'y connecte).
"""
import argparse
import socket
import sys
import threading
import time
from collections import deque


def log(msg):
    print(time.strftime("[%H:%M:%S] ") + "répéteur : " + msg, flush=True)


def listener(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(1)
    return s


class Repeater:
    def __init__(self, a):
        self.w, self.h = (int(x) for x in a.size.lower().split("x"))
        self.frame_bytes = self.w * self.h * 3 // 2
        self.fps = a.fps
        self.rate, self.channels = a.rate, a.channels
        self.bytes_per_sample = 2 * self.channels
        self.audio_tick = 0.02  # 20 ms
        self.audio_chunk = int(self.rate * self.audio_tick) * self.bytes_per_sample
        self.prefill = int(self.rate * a.prefill_ms / 1000) * self.bytes_per_sample
        self.max_fifo = int(self.rate * a.max_ms / 1000) * self.bytes_per_sample
        self.a = a

        # image noire yuv420p tant que rien n'est arrivé
        self.latest = bytes([16]) * (self.w * self.h) + bytes([128]) * (self.w * self.h // 2)
        self.frame_lock = threading.Lock()
        self.fifo = deque()
        self.fifo_len = 0
        self.fifo_lock = threading.Lock()
        self.audio_started = False

        # compteurs pour le journal
        self.frames_in = self.frames_out = self.repeated = 0
        self.silence_chunks = self.skipped_bytes = 0
        self.video_connected = self.audio_connected = False

    # ---------------------------------------------------------------- entrées (décodeur)
    def video_in(self):
        srv = listener(self.a.in_video)
        while True:
            conn, _ = srv.accept()
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
            self.video_connected = True
            log(f"décodeur connecté (vidéo {self.w}x{self.h})")
            buf = bytearray(self.frame_bytes)
            view = memoryview(buf)
            try:
                while True:
                    got = 0
                    while got < self.frame_bytes:
                        n = conn.recv_into(view[got:], self.frame_bytes - got)
                        if n == 0:
                            raise ConnectionError
                        got += n
                    with self.frame_lock:
                        self.latest = bytes(buf)
                    self.frames_in += 1
            except (ConnectionError, OSError):
                pass
            finally:
                conn.close()
                self.video_connected = False
                log("décodeur déconnecté (vidéo) : dernière image répétée en attendant")

    def audio_in(self):
        srv = listener(self.a.in_audio)
        while True:
            conn, _ = srv.accept()
            self.audio_connected = True
            log("décodeur connecté (son)")
            try:
                while True:
                    data = conn.recv(65536)
                    if not data:
                        break
                    with self.fifo_lock:
                        self.fifo.append(data)
                        self.fifo_len += len(data)
                        if self.fifo_len > self.max_fifo:
                            # trop de retard accumulé : on saute en avant (on garde le plus récent)
                            while self.fifo_len > self.prefill and self.fifo:
                                old = self.fifo.popleft()
                                self.fifo_len -= len(old)
                                self.skipped_bytes += len(old)
            except OSError:
                pass
            finally:
                conn.close()
                self.audio_connected = False
                log("décodeur déconnecté (son) : silence en attendant")

    def pop_audio(self):
        """Un tick de son (20 ms) : PCM de la file ou silence."""
        with self.fifo_lock:
            if not self.audio_started:
                if self.fifo_len < self.prefill:
                    self.silence_chunks += 1
                    return None
                self.audio_started = True
            if self.fifo_len < self.audio_chunk:
                # file vide : silence, et on redemande un petit préchargement avant de reprendre
                self.audio_started = False
                self.silence_chunks += 1
                return None
            out = bytearray()
            while len(out) < self.audio_chunk:
                need = self.audio_chunk - len(out)
                head = self.fifo[0]
                if len(head) <= need:
                    out += head
                    self.fifo.popleft()
                else:
                    out += head[:need]
                    self.fifo[0] = head[need:]
            self.fifo_len -= self.audio_chunk
            return bytes(out)

    # ---------------------------------------------------------------- sortie (encodeur)
    def output(self):
        srv_v = listener(self.a.out_video)
        srv_a = listener(self.a.out_audio)
        while True:
            log(f"en attente de l'encodeur sur {self.a.out_video} (vidéo) et {self.a.out_audio} (son)")
            # L'encodeur ffmpeg ouvre et sonde ses entrées l'une après l'autre : la vidéo part dès sa connexion,
            # le son dès la sienne (rattrapé par du silence), chacun dans son thread pour qu'un envoi bloqué
            # d'un côté ne gèle jamais l'autre. Aucun tick n'est jamais sauté : ffmpeg horodate par comptage
            # d'images et d'échantillons, sauter un tick décalerait son et image pour de bon.
            cv, _ = srv_v.accept()
            cv.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 8 << 20)
            t0 = time.perf_counter()
            log("encodeur connecté (vidéo) : sortie continue démarrée")
            with self.fifo_lock:
                self.fifo.clear()
                self.fifo_len = 0
                self.audio_started = False
            socks = []  # fermés ensemble dès qu'un des deux envois échoue
            socks.append(cv)
            tv = threading.Thread(target=self.video_out, args=(cv, t0, socks), daemon=True)
            tv.start()  # la vidéo part tout de suite : ffmpeg n'ouvre l'entrée son qu'après avoir lu une image
            ca, _ = srv_a.accept()
            ca.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 << 20)
            socks.append(ca)
            log(f"encodeur connecté (son), {(time.perf_counter() - t0) * 1000:.0f} ms après la vidéo")
            ta = threading.Thread(target=self.audio_out, args=(ca, t0, socks), daemon=True)
            ta.start()
            tv.join()
            ta.join()
            log("encodeur déconnecté")

    @staticmethod
    def _close_all(socks):
        for s in socks:
            try:
                s.close()
            except OSError:
                pass

    def video_out(self, cv, t0, socks):
        period = 1.0 / self.fps
        next_v = t0
        last_in = self.frames_in
        try:
            while True:
                now = time.perf_counter()
                if now < next_v:
                    time.sleep(next_v - now)
                with self.frame_lock:
                    frame = self.latest
                cv.sendall(frame)
                self.frames_out += 1
                if self.frames_in == last_in:
                    self.repeated += 1
                last_in = self.frames_in
                next_v += period
        except OSError:
            self._close_all(socks)

    def audio_out(self, ca, t0, socks):
        silence = bytes(self.audio_chunk)
        next_a = t0
        try:
            while True:
                now = time.perf_counter()
                if now < next_a:
                    time.sleep(next_a - now)
                chunk = self.pop_audio()
                ca.sendall(chunk if chunk is not None else silence)
                next_a += self.audio_tick
        except OSError:
            self._close_all(socks)

    def stats(self):
        prev = (0, 0, 0, 0)
        while True:
            time.sleep(10)
            cur = (self.frames_in, self.frames_out, self.repeated, self.silence_chunks)
            d = [c - p for c, p in zip(cur, prev)]
            prev = cur
            with self.fifo_lock:
                fifo_ms = self.fifo_len / self.bytes_per_sample / self.rate * 1000
            log(
                f"10 s : images reçues {d[0]}, envoyées {d[1]} (dont répétées {d[2]}), "
                f"silence inséré {d[3] * 20} ms, file son {fifo_ms:.0f} ms, "
                f"décodeur {'connecté' if self.video_connected else 'absent'}"
            )

    def run(self):
        for fn in (self.video_in, self.audio_in, self.output, self.stats):
            threading.Thread(target=fn, daemon=True).start()
        log(f"prêt : {self.w}x{self.h} à {self.fps} i/s, son {self.rate} Hz x{self.channels}, "
            f"préchargement {self.a.prefill_ms} ms, plafond {self.a.max_ms} ms")
        while True:
            time.sleep(3600)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-video", type=int, default=9021)
    ap.add_argument("--in-audio", type=int, default=9022)
    ap.add_argument("--out-video", type=int, default=9031)
    ap.add_argument("--out-audio", type=int, default=9032)
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--rate", type=int, default=48000)
    ap.add_argument("--channels", type=int, default=2)
    ap.add_argument("--prefill-ms", type=int, default=150, help="son mis en réserve avant de commencer à le jouer")
    ap.add_argument("--max-ms", type=int, default=600, help="au-delà, on saute en avant pour ne pas prendre de retard")
    try:
        Repeater(ap.parse_args()).run()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
