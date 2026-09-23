"""Récepteur OBS de TurboIRL : décodeur ffmpeg → répéteur temps réel → encodeur ffmpeg → udp://127.0.0.1:9001.

Pourquoi trois étages : la source multimédia d'OBS retient le son tant qu'elle ne reçoit pas d'image plus
récente. Quand le téléphone retient la vidéo (mode critique en zone morte), ffmpeg seul ne peut pas dupliquer
la dernière image avant d'avoir reçu la suivante : OBS restait sans image ET sans son (6 s de silence sur le
test du 23/09 18h20 alors que le son arrivait en continu). Ici :
  - le décodeur ffmpeg (relancé à chaque session SRT) sort les images brutes 720p à cadence constante et le PCM
    48 kHz sur deux sockets TCP locaux, plus le PTS de chaque image et de chaque bloc audio sur stdout
    via les filtres metadata/ametadata ; il écrit aussi le dump brut ;
  - le répéteur (ce script) joue le son sur l'horloge murale (file de 700 ms, silence si vide) et y asservit
    l'image : à chaque tick il montre la dernière image dont le PTS est atteint par le contenu audio joué
    (PTS de l'image k = ancre de la session + k/30, le filtre fps garantissant la cadence constante) ;
    file vide → image répétée, image en retard → rattrapée sans être montrée. Son et image ne peuvent pas se
    désaligner, quelle que soit la façon dont le décodeur les livre (rafales de 340 ms, image clé tardive) ;
  - l'encodeur ffmpeg (jamais relancé) lit ce flux régulier et l'envoie à OBS, qui ne voit jamais de trou.

Usage :
  python tools/receiver.py --source "srt://0.0.0.0:9000?mode=listener&latency=12000000" --dump-dir dumps
  python tools/receiver.py --source "udp://127.0.0.1:9003?timeout=5000000" --once      (relecture, voir replay.ps1)
"""
import argparse
import os
import re
import socket
import subprocess
import sys
import threading
import time
from collections import deque

TS_PACKET = 188


def log(msg):
    print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


def listener(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(1)
    return s


class Receiver:
    def __init__(self, a):
        self.a = a
        self.w, self.h = (int(x) for x in a.size.lower().split("x"))
        self.frame_bytes = self.w * self.h * 3 // 2
        self.fps = a.fps
        self.rate, self.channels = 48000, 2
        self.bps = 2 * self.channels                     # octets par échantillon stéréo
        self.audio_tick = 0.02
        self.audio_chunk = int(self.rate * self.audio_tick) * self.bps
        self.prefill = int(self.rate * a.prefill_ms / 1000) * self.bps
        self.slack = int(self.rate * 0.3) * self.bps
        self.max_fifo = int(self.rate * a.max_ms / 1000) * self.bps

        self.lock = threading.Lock()
        # son : file d'octets PCM ; chaque session de décodeur ouvre une « époque » (offset d'octets, PTS du 1er bloc)
        self.fifo = deque()
        self.fifo_len = 0
        self.audio_started = False
        self.audio_in_total = 0        # octets reçus depuis toujours
        self.audio_out_total = 0       # octets consommés (joués ou sautés)
        self.epochs = deque()          # (offset d'octets, session, pts_time du premier bloc)
        self.audio_epoch = None        # époque courante côté lecture : (offset, session, pts0)
        self.session = 0               # numéro de session de décodeur
        self.audio_session_first_pts = {}
        self.ticks = 0
        # image : file (session, pts_time, image). Le filtre fps du décodeur sort une cadence strictement constante,
        # donc PTS(image k) = ancre + k/fps : l'image k reçoit son PTS par comptage, sans dépendre de l'arrivée des
        # lignes (les images brutes peuvent arriver 0,5 s après leurs lignes quand le décodeur rattrape un trou).
        # Les lignes de PTS (stdout) servent à poser l'ancre et à détecter une discontinuité (filtre réinitialisé).
        self.frames = deque()
        self.video_anchor = {}             # session → (pts de la première ligne, index de ligne correspondant)
        self.video_line_index = {}         # session → nombre de lignes src=v lues
        self.video_frame_index = {}        # session → nombre d'images brutes reçues
        self.frames_unanchored = deque()   # images reçues avant la première ligne de la session
        self.line_times = deque()          # (index de ligne, heure) pour mesurer le retard des images sur les lignes
        self.frame_line_lag = 0.0
        self.latest = bytes([16]) * (self.w * self.h) + bytes([128]) * (self.w * self.h // 2)
        self.frames_in = self.frames_out = self.repeated = self.distinct = self.late = self.skipped = 0
        self.silence_chunks = self.skipped_audio = 0
        self.sync_worst = 0.0
        self.pts_lines = 0  # lignes « src=v » lues (doit suivre frames_in ; sinon l'appariement par rang dérive)
        self.decoder_connected = False
        self.fifo_min, self.fifo_max = 1 << 30, 0
        self.fq_min, self.fq_max = 1 << 30, 0
        self.encoder = None
        self.decoder = None

    # ------------------------------------------------------------------ décodeur (sessions)
    def decoder_args(self, dump):
        a = self.a
        args = [
            "ffmpeg", "-hide_banner", "-loglevel", "warning", "-nostats",
            # nobuffer/low_delay : pas de rafale au démarrage ; -threads 1 : le décodeur HEVC multi-thread garde ~16 images
            # en attente, soit 3 s de retard à 5 i/s (mode dégradé)
            "-fflags", "+genpts+nobuffer", "-flags", "low_delay", "-analyzeduration", "2000000", "-probesize", "1000000",
            "-dts_delta_threshold", "1000", "-threads", "1",
            "-i", a.source,
            # images brutes 720p à cadence constante (1 image = 1/30 s de contenu) ; le PTS de chaque image et de
            # chaque bloc audio est imprimé sur stdout par les filtres metadata (étiquette src=v / src=a ajoutée,
            # sans métadonnée le filtre n'imprimerait rien ; direct=1 sinon il tamponne 32 Ko, soit 13 s de lignes)
            # la cadence constante est faite par le filtre fps AVANT l'impression des PTS (avec -fps_mode cfr, les
            # doublons/suppressions se feraient après les filtres et les lignes ne correspondraient plus aux images)
            "-map", "0:v:0", "-fps_mode", "passthrough",
            "-vf", f"scale={self.w}:{self.h},format=yuv420p,fps={self.fps},metadata=mode=add:key=src:value=v,metadata=mode=print:file=-:direct=1",
            # -thread_queue_size 1 (sortie) : la file de muxage de ffmpeg, une fois remplie par la rafale de doublons qui
            # suit un trou vidéo, restait pleine (16 images = 0,5 s de retard permanent des images sur leurs PTS)
            "-thread_queue_size", "1", "-flush_packets", "1", "-f", "rawvideo", f"tcp://127.0.0.1:{a.in_video}",
            # PCM 48 kHz stéréo continu (aresample=async comble/rogne les sauts d'horodatage)
            "-map", "0:a:0", "-af", "aresample=async=1000,ametadata=mode=add:key=src:value=a,ametadata=mode=print:file=-:direct=1",
            "-thread_queue_size", "1", "-flush_packets", "1", "-f", "s16le", "-ar", str(self.rate), "-ac", str(self.channels),
            f"tcp://127.0.0.1:{a.in_audio}",
        ]
        if dump:
            # -pes_payload_size 0 : un PES par trame audio comme sur le fil (par défaut ffmpeg en regroupe 16, et la
            # relecture du dump livrait alors le son par rafales de 340 ms qui n'existent pas en direct)
            args += ["-map", "0", "-c", "copy", "-max_interleave_delta", "200000", "-pes_payload_size", "0",
                     "-f", "mpegts", dump]
        return args

    def run_decoder_sessions(self):
        while True:
            self.session += 1
            dump = None
            if self.a.dump_dir:
                os.makedirs(self.a.dump_dir, exist_ok=True)
                dump = os.path.join(self.a.dump_dir, time.strftime("dump-%Y%m%d-%H%M%S.ts"))
            log(f"session {self.session} : attente du téléphone..." + (f" (dump : {dump})" if dump else ""))
            p = subprocess.Popen(self.decoder_args(dump), stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
            self.decoder = p
            tv = threading.Thread(target=self.read_video_pts, args=(p.stdout, self.session), daemon=True)
            ta = threading.Thread(target=self.read_decoder_log, args=(p.stderr, self.session), daemon=True)
            tv.start()
            ta.start()
            p.wait()
            tv.join(2)
            ta.join(2)
            if dump and os.path.exists(dump) and os.path.getsize(dump) < 100_000:
                os.remove(dump)  # connexion sans flux
            log(f"session {self.session} terminée (téléphone déconnecté), nouvelle session dans 1 s")
            if self.a.once:
                return
            time.sleep(1)

    PTS_RE = re.compile(rb"pts_time:(-?[0-9.]+)")

    def read_video_pts(self, pipe, session):
        """stdout du décodeur : « frame:N pts:P pts_time:T » puis « src=v » ou « src=a » pour chaque image / bloc audio."""
        pending = None
        for line in iter(pipe.readline, b""):
            m = self.PTS_RE.search(line)
            if m:
                pending = float(m.group(1))
                continue
            if pending is None:
                continue
            if line.startswith(b"src=v"):
                self.pts_lines += 1
                with self.lock:
                    k = self.video_line_index.get(session, 0)
                    self.video_line_index[session] = k + 1
                    self.line_times.append((k, time.perf_counter()))
                    if len(self.line_times) > 600:
                        self.line_times.popleft()
                    anchor = self.video_anchor.get(session)
                    if anchor is None:
                        self.video_anchor[session] = (pending, k)
                        # images arrivées avant la première ligne : elles précèdent l'ancre
                        while self.frames_unanchored:
                            fs, idx, data = self.frames_unanchored.popleft()
                            if fs == session:
                                self.frames.append((fs, pending + (idx - k) / self.fps, data))
                    else:
                        expected = anchor[0] + (k - anchor[1]) / self.fps
                        if abs(pending - expected) > 0.6 / self.fps:
                            log(f"session {session} : discontinuité vidéo de {pending - expected:+.3f} s à la ligne {k}, ancre recalée")
                            self.video_anchor[session] = (pending, k)
            elif line.startswith(b"src=a"):
                if session not in self.audio_session_first_pts:
                    self.audio_session_first_pts[session] = pending
            pending = None

    def read_decoder_log(self, pipe, session):
        for line in iter(pipe.readline, b""):
            text = line.decode("utf-8", errors="replace").rstrip()
            if not text or "Could not find ref with POC" in text or "Error constructing the frame RPS" in text                     or "Skipping invalid undecodable NALU" in text or "Last message repeated" in text                     or "PPS id out of range" in text or "cu_qp_delta" in text:
                continue
            log("décodeur : " + text)

    # ------------------------------------------------------------------ entrées (sockets du décodeur)
    def video_in(self):
        srv = listener(self.a.in_video)
        while True:
            conn, _ = srv.accept()
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
            session = self.session
            self.decoder_connected = True
            with self.lock:
                # nouvelle session : on repart propre côté image (l'ancre et les index de la session arrivent avec elle)
                self.frames.clear()
                self.frames_unanchored.clear()
                self.line_times.clear()
            log(f"session {session} : décodeur connecté (vidéo {self.w}x{self.h})")
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
                    data = bytes(buf)
                    with self.lock:
                        idx = self.video_frame_index.get(session, 0)
                        self.video_frame_index[session] = idx + 1
                        anchor = self.video_anchor.get(session)
                        if anchor is None:
                            self.frames_unanchored.append((session, idx, data))
                        else:
                            self.frames.append((session, anchor[0] + (idx - anchor[1]) / self.fps, data))
                        for li, lt in self.line_times:
                            if li == idx:
                                self.frame_line_lag = max(self.frame_line_lag, time.perf_counter() - lt)
                                break
                        if len(self.frames) > self.fps * 6:
                            self.frames.popleft()  # garde-fou mémoire (décodeur très en avance sur le son : anormal)
                            self.skipped += 1
                    self.frames_in += 1
            except (ConnectionError, OSError):
                pass
            finally:
                conn.close()
                self.decoder_connected = False
                log(f"session {session} : décodeur déconnecté (vidéo), dernière image répétée en attendant")

    def audio_in(self):
        srv = listener(self.a.in_audio)
        while True:
            conn, _ = srv.accept()
            session = self.session
            log(f"session {session} : décodeur connecté (son)")
            first = True
            try:
                while True:
                    data = conn.recv(65536)
                    if not data:
                        break
                    with self.lock:
                        if first:
                            first = False
                            # le PTS du premier bloc arrive par stderr, en général avant les octets ; sinon on attend
                            for _ in range(200):
                                if session in self.audio_session_first_pts:
                                    break
                                self.lock.release()
                                time.sleep(0.01)
                                self.lock.acquire()
                            pts0 = self.audio_session_first_pts.get(session)
                            if pts0 is None:
                                log(f"session {session} : PTS audio initial inconnu, calage image/son approximatif")
                                pts0 = 0.0
                            self.epochs.append((self.audio_in_total, session, pts0))
                        self.fifo.append(data)
                        self.fifo_len += len(data)
                        self.audio_in_total += len(data)
                        self.fifo_max = max(self.fifo_max, self.fifo_len)
                        if self.fifo_len > self.max_fifo:
                            # rafale (reconnexion) : on saute en avant d'un coup jusqu'au préchargement
                            while self.fifo_len > self.prefill and self.fifo:
                                self._drop(len(self.fifo[0]))
            except OSError:
                pass
            finally:
                conn.close()
                log(f"session {session} : décodeur déconnecté (son), silence en attendant")

    # ------------------------------------------------------------------ horloge de contenu
    def _take(self, n):
        out = bytearray()
        while len(out) < n:
            need = n - len(out)
            head = self.fifo[0]
            if len(head) <= need:
                out += head
                self.fifo.popleft()
            else:
                out += head[:need]
                self.fifo[0] = head[need:]
        self.fifo_len -= n
        self.audio_out_total += n
        return bytes(out)

    def _drop(self, n):
        self._take(n)
        self.skipped_audio += n

    def content_time(self):
        """(session, temps de contenu) du son en cours de lecture, d'après les octets consommés."""
        while self.epochs and self.epochs[0][0] <= self.audio_out_total:
            self.audio_epoch = self.epochs.popleft()
        if self.audio_epoch is None:
            return None, None
        off, session, pts0 = self.audio_epoch
        return session, pts0 + (self.audio_out_total - off) / self.bps / self.rate

    def pop_audio(self):
        """Un tick de son (20 ms) : PCM de la file ou None (silence)."""
        with self.lock:
            self.fifo_min = min(self.fifo_min, self.fifo_len)
            if not self.audio_started:
                if self.fifo_len < self.prefill:
                    self.silence_chunks += 1
                    return None
                self.audio_started = True
            if self.fifo_len < self.audio_chunk:
                self.audio_started = False  # file vide : silence, puis on reconstitue la réserve
                self.silence_chunks += 1
                return None
            if self.fifo_len > self.prefill + self.slack and self.ticks % 50 == 0:
                self._drop(self.audio_chunk)  # file trop haute : on saute 20 ms une fois par seconde
            self.ticks += 1
            return self._take(self.audio_chunk)

    def next_frame(self):
        """Un tick d'image : la dernière image dont le PTS est atteint par le son joué (même session)."""
        with self.lock:
            self.fq_min = min(self.fq_min, len(self.frames))
            self.fq_max = max(self.fq_max, len(self.frames))
            session, t = self.content_time()
            new = 0
            while self.frames:
                s, pts, data = self.frames[0]
                if session is None:
                    break
                if s < session:
                    pass  # reste d'une session précédente : on l'écoule
                elif s > session or pts > t + 0.5 / self.fps:
                    break
                self.frames.popleft()
                self.latest = data
                new += 1
                if s == session:
                    self.sync_worst = max(self.sync_worst, t - pts)
                    # une image par tick tant que le retard reste faible (les ticks son et image ne sont pas en
                    # phase) ; on ne rattrape en sautant que si la suivante a plus de 3 périodes de retard
                    if self.frames and t - self.frames[0][1] < 3.0 / self.fps:
                        break
            if new == 0:
                self.repeated += 1
                if session is not None and self.frames and self.frames[0][0] == session:
                    pass
                elif session is not None and not self.frames and self.audio_started:
                    self.late += 1  # le son joue mais l'image de ce moment n'est pas arrivée
            else:
                self.distinct += 1
                self.skipped += new - 1
            return self.latest

    # ------------------------------------------------------------------ sortie (encodeur)
    def encoder_args(self):
        a = self.a
        return [
            "ffmpeg", "-hide_banner", "-loglevel", "warning", "-nostats",
            "-thread_queue_size", "1024", "-probesize", "32", "-analyzeduration", "0",
            "-f", "rawvideo", "-pix_fmt", "yuv420p", "-video_size", a.size, "-framerate", str(self.fps),
            "-i", f"tcp://127.0.0.1:{a.out_video}",
            "-thread_queue_size", "1024", "-probesize", "32", "-analyzeduration", "0",
            "-f", "s16le", "-ar", str(self.rate), "-ac", str(self.channels),
            "-i", f"tcp://127.0.0.1:{a.out_audio}",
            "-map", "0:v", "-map", "1:a",
            # crf 18 plafonné : une image identique à la précédente ne coûte presque rien (en débit imposé x264
            # dépensait 10 Mb/s sur un plan figé)
            "-c:v", "libx264", "-preset", "faster", "-tune", "zerolatency", "-g", "60",
            "-crf", "18", "-maxrate", "10M", "-bufsize", "10M", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "160k", "-max_interleave_delta", "1000000",
            "-f", "mpegts", f"udp://127.0.0.1:{a.obs_port}?pkt_size=1316",
        ]

    def output(self):
        srv_v = listener(self.a.out_video)
        srv_a = listener(self.a.out_audio)
        silence = bytes(self.audio_chunk)
        while True:
            self.encoder = subprocess.Popen(self.encoder_args(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            threading.Thread(target=self.read_encoder_log, args=(self.encoder.stderr,), daemon=True).start()
            # ffmpeg ouvre et sonde ses entrées l'une après l'autre : la vidéo part dès sa connexion, le son dès la
            # sienne, chacun dans son thread ; aucun tick n'est jamais sauté (ffmpeg horodate par comptage)
            cv, _ = srv_v.accept()
            cv.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 8 << 20)
            t0 = time.perf_counter()
            socks = [cv]
            tv = threading.Thread(target=self.video_out, args=(cv, t0, socks), daemon=True)
            tv.start()
            ca, _ = srv_a.accept()
            ca.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 << 20)
            socks.append(ca)
            log(f"encodeur connecté : sortie continue vers udp://127.0.0.1:{self.a.obs_port} (OBS)")
            ta = threading.Thread(target=self.audio_out, args=(ca, t0, socks, silence), daemon=True)
            ta.start()
            tv.join()
            ta.join()
            self.encoder.wait()
            log("encodeur arrêté, relance dans 1 s")
            time.sleep(1)

    def read_encoder_log(self, pipe):
        for line in iter(pipe.readline, b""):
            text = line.decode("utf-8", errors="replace").rstrip()
            if text and "Guessed Channel Layout" not in text:
                log("encodeur : " + text)

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
        try:
            while True:
                now = time.perf_counter()
                if now < next_v:
                    time.sleep(next_v - now)
                cv.sendall(self.next_frame())
                self.frames_out += 1
                next_v += period
        except OSError:
            self._close_all(socks)

    def audio_out(self, ca, t0, socks, silence):
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

    # ------------------------------------------------------------------ journal
    def stats(self):
        prev = (0, 0, 0, 0, 0, 0)
        while True:
            time.sleep(10)
            cur = (self.frames_in, self.frames_out, self.repeated, self.silence_chunks, self.late, self.skipped)
            d = [c - p for c, p in zip(cur, prev)]
            prev = cur
            with self.lock:
                ms = 1000 / self.bps / self.rate
                fifo = f"{self.fifo_min * ms:.0f}-{self.fifo_max * ms:.0f} ms" if self.fifo_max else "vide"
                fq = f"{self.fq_min}-{self.fq_max}"
                unpaired = f"lignes PTS {self.pts_lines} / images {self.frames_in}, image après sa ligne au pire {self.frame_line_lag * 1000:.0f} ms"
                self.frame_line_lag = 0.0
                self.fifo_min, self.fifo_max = 1 << 30, 0
                self.fq_min, self.fq_max = 1 << 30, 0
                worst = self.sync_worst
                self.sync_worst = 0.0
            log(
                f"10 s : images reçues {d[0]}, envoyées {d[1]} (répétées {d[2]}, en retard {d[4]}, sautées {d[5]}), "
                f"file image {fq}, file son {fifo}, silence inséré {d[3] * 20} ms, "
                f"son sauté {self.skipped_audio * ms:.0f} ms au total, image en retard sur le son au pire {worst * 1000:.0f} ms, {unpaired}, "
                f"décodeur {'connecté' if self.decoder_connected else 'absent'}"
            )

    def run(self):
        for fn in (self.video_in, self.audio_in, self.output, self.stats):
            threading.Thread(target=fn, daemon=True).start()
        time.sleep(0.5)
        log(f"récepteur prêt : {self.w}x{self.h} à {self.fps} i/s, son {self.rate} Hz, réserve {self.a.prefill_ms} ms")
        try:
            self.run_decoder_sessions()
            time.sleep(3)  # laisse l'encodeur écouler la fin (relecture)
        finally:
            for p in (self.decoder, self.encoder):
                if p and p.poll() is None:
                    p.kill()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", required=True, help="entrée ffmpeg : srt://0.0.0.0:9000?mode=listener&latency=... ou udp://...")
    ap.add_argument("--dump-dir", default="", help="dossier des dumps bruts (vide = pas de dump)")
    ap.add_argument("--obs-port", type=int, default=9001)
    ap.add_argument("--once", action="store_true", help="une seule session de décodeur puis fin (relecture)")
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--fps", type=int, default=30)
    # 700 ms : le téléphone (≤ 0.99) met 16 trames AAC (340 ms) dans chaque PES audio, son et image arrivent par
    # rafales de 340 ms ; avec moins de réserve la file son se vidait (micro-silences)
    ap.add_argument("--prefill-ms", type=int, default=700)
    ap.add_argument("--max-ms", type=int, default=1500)
    ap.add_argument("--in-video", type=int, default=9021)
    ap.add_argument("--in-audio", type=int, default=9022)
    ap.add_argument("--out-video", type=int, default=9031)
    ap.add_argument("--out-audio", type=int, default=9032)
    try:
        Receiver(ap.parse_args()).run()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
