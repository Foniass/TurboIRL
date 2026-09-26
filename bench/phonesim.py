"""Simulateur de téléphone pour le banc de transport (Linux/WSL, python3 + ffmpeg avec SRT).

Reproduit la chaîne de l'appli entre la caméra et le VPS, sans caméra ni encodeur :
  - source vidéo : une gamme de fichiers TS HEVC pré-encodés (bench/ladder, même contenu, débits différents, image
    clé chaque seconde) ; le simulateur choisit le fichier selon le débit cible et bascule aux images clés ;
  - régulation (port fidèle de AdaptiveBitrate.kt / SrtSender.kt / FlvToTsRelay.kt) : frein sur le tampon d'envoi
    SRT, paliers ×0,7 / ×1,15, suspension de la vidéo à 60 % du tampon (mode dégradé 150 kb/s à 5 i/s, 5 s minimum,
    doublé en cas de rechute), vidéo retenue à 80 % (le son seul part) ;
  - envoi SRT : ffmpeg (libsrt) lit le TS du simulateur sur un port UDP local et publie en SRT vers le répartiteur ;
  - répartiteur de liens : la politique de LinkMux.kt telle que copiée dans tools/bondclient.py (parts, plafond
    contre le bufferbloat, liens suspects, rejeu, son sur tous les liens), avec des liens simulés variables dans le
    temps (profils : débit, latence, gigue, pertes, rafales, file d'attente, coupures) vers le bond ;
  - tampon d'envoi SRT estimé en observant les paquets de données et les accusés de réception qui transitent.

  python3 bench/phonesim.py --scenario s.json --path turboirl-bench1 --ports 9100 --seconds 120 --out r.json
Le scénario (JSON) décrit les liens et le téléphone ; voir bench/scenario.py pour en générer.
"""
import argparse
import collections
import heapq
import json
import os
import random
import select
import socket
import struct
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "tools"))
import bondclient as bc  # noqa: E402

TS = 188
LADDER_DIR = os.path.join(ROOT, "bench", "ladder")


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


# ---------------------------------------------------------------- source TS : gamme de fichiers pré-encodés
def pes_times(payload):
    """(pts, dts) en 90 kHz d'un début de PES, ou None."""
    if len(payload) < 14 or payload[0:3] != b"\x00\x00\x01":
        return None
    flags = payload[7]
    if not flags & 0x80:
        return None

    def read(p):
        return ((p[0] >> 1) & 0x07) << 30 | p[1] << 22 | (p[2] >> 1) << 15 | p[3] << 7 | p[4] >> 1
    pts = read(payload[9:14])
    dts = read(payload[14:19]) if flags & 0x40 and len(payload) >= 19 else pts
    return pts, dts


def hevc_keyframe(payload):
    """Le PES contient-il une image clé HEVC (NAL IRAP 16-23, ou VPS/SPS) ?"""
    hl = 9 + payload[8] if len(payload) > 8 else 0
    data = payload[hl:hl + 400]
    i = 0
    while True:
        i = data.find(b"\x00\x00\x01", i)
        if i < 0 or i + 3 >= len(data):
            return False
        t = (data[i + 3] >> 1) & 0x3F
        if 16 <= t <= 23 or t in (32, 33):
            return True
        if t in (0, 1, 8, 9):
            return False
        i += 3


class Au:
    __slots__ = ("pts", "dts", "kind", "key", "packets")

    def __init__(self, pts, dts, kind, key, packets):
        self.pts, self.dts, self.kind, self.key, self.packets = pts, dts, kind, key, packets


def parse_ts_file(path):
    """Fichier TS → (psi_packets, aus) ; aus triées par pts, kind 'v'/'a'."""
    data = open(path, "rb").read()
    n = len(data) // TS
    pmt_pids, pids = set(), {}
    psi = []
    aus = []
    cur = {}
    for i in range(n):
        pkt = data[i * TS:(i + 1) * TS]
        if pkt[0] != 0x47:
            continue
        pid = (pkt[1] & 0x1F) << 8 | pkt[2]
        pusi = pkt[1] & 0x40
        afc = (pkt[3] >> 4) & 0x3
        off = 4 + (1 + pkt[4] if afc & 0x2 else 0)
        payload = pkt[off:] if afc & 0x1 and off < TS else b""
        if pid == 0:
            if len(psi) < 2:
                psi.append(pkt)
            p = payload[1 + payload[0]:] if pusi else payload
            sec_len = (p[1] & 0x0F) << 8 | p[2]
            for j in range(8, min(3 + sec_len - 4, len(p) - 3), 4):
                if p[j] << 8 | p[j + 1]:
                    pmt_pids.add((p[j + 2] & 0x1F) << 8 | p[j + 3])
            continue
        if pid in pmt_pids:
            if len(psi) < 2:
                psi.append(pkt)
            p = payload[1 + payload[0]:] if pusi else payload
            sec_len = (p[1] & 0x0F) << 8 | p[2]
            info_len = (p[10] & 0x0F) << 8 | p[11]
            j = 12 + info_len
            end = 3 + sec_len - 4
            while j + 5 <= min(end, len(p)):
                st, epid = p[j], (p[j + 1] & 0x1F) << 8 | p[j + 2]
                pids[epid] = "v" if st in (0x24, 0x1B) else "a" if st in (0x0F, 0x11) else None
                j += 5 + ((p[j + 3] & 0x0F) << 8 | p[j + 4])
            continue
        kind = pids.get(pid)
        if kind is None:
            continue
        if pusi:
            if pid in cur:
                aus.append(cur.pop(pid))
            t = pes_times(payload)
            if t is None:
                continue
            cur[pid] = Au(t[0], t[1], kind, kind == "v" and hevc_keyframe(payload), [pkt])
        elif pid in cur:
            cur[pid].packets.append(pkt)
    aus.extend(cur.values())
    aus.sort(key=lambda a: (a.dts, a.kind))
    return psi, aus


def patch_times(pkt, offset):
    """Décale PTS/DTS (PES) et PCR (champ d'adaptation) d'un paquet TS, pour boucler la source sans retour en arrière."""
    b = bytearray(pkt)
    afc = (b[3] >> 4) & 0x3
    off = 4
    if afc & 0x2:
        al = b[4]
        if al >= 7 and b[5] & 0x10:
            base = (b[6] << 25) | (b[7] << 17) | (b[8] << 9) | (b[9] << 1) | (b[10] >> 7)
            base = (base + offset) & 0x1FFFFFFFF
            b[6] = (base >> 25) & 0xFF
            b[7] = (base >> 17) & 0xFF
            b[8] = (base >> 9) & 0xFF
            b[9] = (base >> 1) & 0xFF
            b[10] = (b[10] & 0x7F) | ((base & 1) << 7)
        off += 1 + al
    if b[1] & 0x40 and afc & 0x1 and off + 19 <= TS and b[off:off + 3] == b"\x00\x00\x01":
        flags = b[off + 7]

        def write(at, v, marker):
            v &= 0x1FFFFFFFF
            b[at] = marker | ((v >> 29) & 0x0E) | 1
            b[at + 1] = (v >> 22) & 0xFF
            b[at + 2] = ((v >> 14) & 0xFE) | 1
            b[at + 3] = (v >> 7) & 0xFF
            b[at + 4] = ((v << 1) & 0xFE) | 1
        if flags & 0x80:
            t = pes_times(bytes(b[off:off + 19]))
            if t:
                write(off + 9, t[0] + offset, 0x30 if flags & 0x40 else 0x20)
                if flags & 0x40:
                    write(off + 14, t[1] + offset, 0x10)
    return bytes(b)


class Ladder:
    """Toute la gamme en mémoire : les AU vidéo par débit, le son d'un fichier de référence, les PSI."""

    def __init__(self, directory=LADDER_DIR):
        self.video = {}     # kbps → (fps, [Au])
        self.audio = None
        self.psi = None
        self.duration = 0   # 90 kHz
        for name in sorted(os.listdir(directory)):
            if not name.endswith(".ts"):
                continue
            stem = name[1:-3]
            kbps, fps = (stem.split("f") + ["30"])[:2]
            psi, aus = parse_ts_file(os.path.join(directory, name))
            v = [a for a in aus if a.kind == "v"]
            key = (int(kbps), int(fps))
            self.video[key] = v
            if self.audio is None or int(kbps) == 3500:
                self.audio = [a for a in aus if a.kind == "a"]
                self.psi = psi
            self.duration = max(self.duration, max(a.pts for a in aus) + 3003)
        if not self.video:
            raise SystemExit(f"aucun fichier dans {directory} (encoder la gamme d'abord)")

    def pick(self, kbps, fps):
        """Le fichier dont le débit est le plus proche, à la cadence voulue (30, 15 ou 5 i/s)."""
        cands = [k for k in self.video if k[1] == fps] or list(self.video)
        return min(cands, key=lambda k: abs(k[0] - kbps))


# ---------------------------------------------------------------- liens simulés variables dans le temps
class ProfileLink(bc.Link):
    """Lien de bondclient dont les défauts suivent un profil dans le temps (segments), avec gigue, rafales de pertes
    et coupures programmées."""

    def __init__(self, lid, spec, vps, t0):
        super().__init__(lid, f"{spec['name']},share={spec.get('share', 0.5)}", vps)
        self.spec = spec
        self.t_start = t0
        self.segments = spec.get("segments") or [{"t": 0, "kbps": 0, "delay": 0}]
        self.outages = [(float(a), float(b)) for a, b in spec.get("outages", [])]
        self.seg = None
        self.jitter = 0.0
        self.burst_p = 0.0        # probabilité d'entrer en rafale de pertes, par paquet
        self.burst_len = 0.0      # durée moyenne d'une rafale (s)
        self.burst_until = 0.0
        self.rng2 = random.Random(lid * 7919 + 1)
        self.apply(t0)

    def apply(self, now):
        t = now - self.t_start
        seg = self.segments[0]
        for s in self.segments:
            if s["t"] <= t:
                seg = s
        if seg is not self.seg:
            self.seg = seg
            self.kbps = float(seg.get("kbps", 0))
            self.delay = float(seg.get("delay", 0)) / 1000
            self.loss = float(seg.get("loss", 0))
            self.queue_s = float(seg.get("queue", 500)) / 1000
            self.jitter = float(seg.get("jitter", 0)) / 1000
            self.burst_p = float(seg.get("burst_p", 0))
            self.burst_len = float(seg.get("burst_len", 0))

    def down(self, now):
        t = now - self.t_start
        return any(a <= t < a + b for a, b in self.outages)

    def impaired_send(self, pkt, now):
        if self.burst_p and now > self.burst_until and self.rng2.random() < self.burst_p:
            self.burst_until = now + self.rng2.expovariate(1.0 / max(self.burst_len, 0.01))
        if now < self.burst_until:
            self.sends.append((now, len(pkt)))
            self.window_bytes += len(pkt)
            return
        saved = self.delay
        if self.jitter:
            self.delay = saved + self.rng2.random() * self.jitter
        try:
            super().impaired_send(pkt, now)
        finally:
            self.delay = saved


# ---------------------------------------------------------------- tampon d'envoi SRT observé
class SrtObserver:
    """Estime ce que libsrt sait de son tampon d'envoi : paquets de données vus au départ, accusés de réception vus
    au retour. msSndBuf = âge du plus ancien paquet non acquitté ; au-delà de 1,25 × latence libsrt l'aurait jeté."""

    def __init__(self, latency_ms):
        self.latency = latency_ms / 1000
        self.pending = collections.OrderedDict()   # seq → (heure d'envoi, octets)
        self.dropped = 0
        self.retrans = 0
        self.last_ack = None

    def sent(self, payload, now):
        seq = bc.data_seq(payload)
        if seq is None:
            return
        if bc.retransmitted(payload):
            self.retrans += 1
            return
        self.pending[seq] = (now, len(payload))

    def returned(self, payload):
        if len(payload) < 20 or not payload[0] & 0x80:
            return
        if struct.unpack_from("!H", payload, 0)[0] & 0x7FFF != 2:
            return
        ack = struct.unpack_from("!I", payload, 16)[0] & 0x7FFFFFFF
        self.last_ack = ack
        for seq in list(self.pending):
            if (ack - seq) % (1 << 31) < (1 << 30) and seq != ack:
                del self.pending[seq]
            else:
                break

    def buffer_ms(self, now):
        for seq, (t, _) in list(self.pending.items()):
            if now - t > self.latency * 1.25:
                del self.pending[seq]
                self.dropped += 1
            else:
                break
        if not self.pending:
            return 0
        t, _ = next(iter(self.pending.values()))
        return int((now - t) * 1000)


# ---------------------------------------------------------------- régulation (port de l'appli)
class Phone:
    DEGRADED_KBPS, LOW_KBPS = 150, 700

    def __init__(self, latency_ms, min_kbps, max_kbps, audio_kbps):
        self.latency = latency_ms
        self.min_kbps, self.max_kbps = min_kbps, max_kbps
        self.audio_overhead = audio_kbps * 13 // 10 + 40
        self.hi = min(max(latency_ms // 4, 400), 2500)
        self.lo = min(max(latency_ms // 20, 100), 500)
        self.congest_on, self.congest_off = int(latency_ms * 0.6), latency_ms // 5
        self.critical_on, self.critical_off = latency_ms * 4 // 5, latency_ms // 2
        self.target = min(max(max_kbps * 4 // 10, min_kbps), max_kbps)
        self.congested = self.critical = False
        self.suspended = False
        self.hold_ms, self.suspended_at, self.resumed_at = 5000, 0.0, 0.0
        self.last_cut = self.last_raise = self.drained_since = 0.0
        self.last_dropped = 0
        self.events = []      # (t, texte)
        self.timeline = []    # par 500 ms : (t, target, buffer_ms, état)

    def state(self):
        return "retenue" if self.critical else "dégradée" if self.suspended else "normale"

    def wanted(self):
        """(kbps, fps) à envoyer maintenant ; None = pas de vidéo du tout."""
        if self.critical:
            return None
        if self.suspended:
            return (self.DEGRADED_KBPS, 5)
        return (self.target, 15 if self.target < self.LOW_KBPS else 30)

    def on_stats(self, now, buffer_ms, dropped_total, egress_kbps, on_keyframe_soon):
        # SrtSender (toutes les 250 ms dans l'appli) : hystérésis congestion / critique
        if buffer_ms >= self.congest_on:
            self.congested = True
        elif buffer_ms <= self.congest_off:
            self.congested = False
        if not self.critical and buffer_ms >= self.critical_on:
            self.critical = True
            self.events.append((now, f"Tampon SRT à {buffer_ms} ms : vidéo retenue, seul le son part"))
        elif self.critical and buffer_ms <= self.critical_off:
            self.critical = False
            self.events.append((now, f"Tampon SRT redescendu à {buffer_ms} ms : vidéo relâchée"))
        # FlvToTsRelay : suspension (5 s minimum, doublée en cas de rechute sous 15 s, 60 s max)
        if self.suspended:
            ms = (now - self.suspended_at) * 1000
            if not self.congested and ms >= self.hold_ms and on_keyframe_soon:
                self.suspended = False
                self.resumed_at = now
                self.events.append((now, f"Vidéo reprise après {ms / 1000:.1f} s"))
                self.target = self.min_kbps
                self.last_cut = now
        elif self.congested:
            since = (now - self.resumed_at) * 1000 if self.resumed_at else 1e9
            self.hold_ms = min(self.hold_ms * 2, 60000) if since < 15000 else 5000
            self.suspended = True
            self.suspended_at = now
            self.target = self.min_kbps
            self.last_cut = now
            self.events.append((now, f"Liaison saturée : vidéo suspendue ({self.hold_ms // 1000} s minimum), le son continue"))
        # AdaptiveBitrate (toutes les 500 ms)
        dropped = dropped_total - self.last_dropped
        self.last_dropped = dropped_total
        before = self.target
        if buffer_ms > self.hi or dropped > 0:
            self.drained_since = 0.0
            if now - self.last_cut > 1.0:
                from_link = (egress_kbps - self.audio_overhead) * 8 // 10 if egress_kbps > 0 else self.target
                self.target = min(max(min(self.target * 7 // 10, from_link), self.min_kbps), self.max_kbps)
                self.last_cut = now
        elif buffer_ms < self.lo and not self.suspended:
            if not self.drained_since:
                self.drained_since = now
            if now - self.drained_since > 2.0 and now - self.last_cut > 3.0 and now - self.last_raise > 1.5:
                self.target = min(max(self.target * 115 // 100 + 50, self.min_kbps), self.max_kbps)
                self.last_raise = now
        else:
            self.drained_since = 0.0
        if self.target != before and (self.target < before):
            self.events.append((now, f"Encodeur → {self.target} kb/s (tampon SRT {buffer_ms} ms, sortie {egress_kbps} kb/s"
                                     + (f", {dropped} perdus" if dropped else "") + ")"))
        self.timeline.append((round(now, 1), self.target, buffer_ms, self.state()))


# ---------------------------------------------------------------- le simulateur
class Sim:
    def __init__(self, a, scenario):
        self.a = a
        self.sc = scenario
        ph = scenario.get("phone", {})
        self.latency = int(ph.get("latency_ms", 13000))
        self.phone = Phone(self.latency, int(ph.get("min_kbps", 400)), int(ph.get("max_kbps", 3500)), int(ph.get("audio_kbps", 64)))
        self.ladder = Ladder()
        self.obs = SrtObserver(self.latency)
        self.ts_port = a.ports
        self.mux_port = a.ports + 1
        self.running = True
        self.video_bytes = collections.deque()   # (t, octets) vidéo émis, fenêtre 1 s
        self.video_total = 0
        self.audio_total = 0
        self.switches = 0
        self.t_state = {"normale": 0.0, "dégradée": 0.0, "retenue": 0.0}
        self.video_kbps_samples = []
        self.ff = None

    # ---- émission TS vers ffmpeg
    def emitter(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        dest = ("127.0.0.1", self.ts_port)
        lad = self.ladder
        t0 = time.time() + 0.5
        loop = 0
        dur = lad.duration
        cc = {}

        def send(packets, offset):
            out = bytearray()
            for pkt in packets:
                pkt = patch_times(pkt, offset) if offset else pkt
                pid = (pkt[1] & 0x1F) << 8 | pkt[2]
                b = bytearray(pkt)
                if b[3] & 0x10:   # payload présent : compteur de continuité à nous
                    c = cc.get(pid, 0)
                    b[3] = (b[3] & 0xF0) | c
                    cc[pid] = (c + 1) & 0x0F
                out += b
                if len(out) >= 7 * TS:
                    sock.sendto(bytes(out), dest)
                    out = bytearray()
            if out:
                sock.sendto(bytes(out), dest)

        cur_key = None
        vi = 0            # index dans la liste vidéo courante
        ai = 0
        last_psi = 0.0
        pending_key = None
        while self.running:
            now = time.time()
            media = (now - t0) * 90000   # position de lecture, en 90 kHz, depuis le début
            if media < 0:
                time.sleep(0.005)
                continue
            offset = loop * dur
            pos = media - offset
            if pos >= dur:
                loop += 1
                vi = ai = 0
                continue
            if now - last_psi > 0.1:
                send(lad.psi, 0)
                last_psi = now
            # son : toujours
            audio = lad.audio
            while ai < len(audio) and audio[ai].dts <= pos:
                send(audio[ai].packets, offset)
                self.audio_total += len(audio[ai].packets) * TS
                ai += 1
            # vidéo : selon le régulateur
            want = self.phone.wanted()
            key = lad.pick(*want) if want else None
            if key != cur_key:
                pending_key = key
            if pending_key is not None or cur_key is None:
                # bascule à la prochaine image clé de la nouvelle gamme (même grille de temps pour tous les fichiers)
                new_list = lad.video[pending_key] if pending_key else []
                j = vi if (cur_key and pending_key and lad.video[pending_key] is not None) else 0
                # index de la première AU clé à dts >= pos dans la nouvelle liste
                j = 0
                lo_, hi_ = 0, len(new_list)
                while lo_ < hi_:
                    mid = (lo_ + hi_) // 2
                    if new_list[mid].dts < pos:
                        lo_ = mid + 1
                    else:
                        hi_ = mid
                j = lo_
                while j < len(new_list) and not new_list[j].key:
                    j += 1
                if pending_key is None or (j < len(new_list) and new_list[j].dts <= pos + 3003 * 2):
                    if cur_key is not None and pending_key != cur_key:
                        self.switches += 1
                    cur_key, vi, pending_key = pending_key, j, None
            if cur_key is not None:
                vids = lad.video[cur_key]
                while vi < len(vids) and vids[vi].dts <= pos:
                    au = vids[vi]
                    send(au.packets, offset)
                    n = len(au.packets) * TS
                    self.video_total += n
                    self.video_bytes.append((now, n))
                    vi += 1
            else:
                # pas de vidéo : on avance quand même l'index de référence pour reprendre au bon endroit plus tard
                pass
            time.sleep(0.004)

    def video_kbps(self, now):
        while self.video_bytes and now - self.video_bytes[0][0] > 1.0:
            self.video_bytes.popleft()
        return sum(n for _, n in self.video_bytes) * 8 // 1000

    # ---- ffmpeg : TS local → SRT
    def start_ffmpeg(self):
        url = (f"srt://127.0.0.1:{self.mux_port}?streamid=publish:{self.a.path}:phone:{self.a.token}"
               f"&latency={self.latency}&pkt_size=1128")
        cmd = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "mpegts",
               "-i", f"udp://127.0.0.1:{self.ts_port}?fifo_size=1000000&overrun_nonfatal=1",
               "-map", "0", "-c", "copy", "-flush_packets", "1", "-f", "mpegts", url]
        self.ff = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        threading.Thread(target=self._fflog, daemon=True).start()

    def _fflog(self):
        for line in iter(self.ff.stderr.readline, b""):
            log("ffmpeg : " + line.decode("utf-8", "replace").rstrip())

    # ---- répartiteur (boucle de bondclient, avec liens à profil)
    def mux_loop(self):
        a = self.a
        vps = (a.vps, 8891)
        t0 = time.time()
        links = [ProfileLink(i, spec, vps, t0) for i, spec in enumerate(self.sc["links"])]
        self.links, self.counters = links, collections.Counter()
        total_share = sum(l.share for l in links) or 1.0
        for l in links:
            l.share /= total_share
        local = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        local.bind(("127.0.0.1", self.mux_port))
        local.setblocking(False)
        sid = random.randint(1, 1 << 31)
        libsrt_addr = None
        last_ping = 0.0
        last_stats = 0.0
        last_apply = 0.0
        seen_at = {}
        counters = self.counters
        seq_link = {}
        phone = self.phone
        while self.running:
            socks = [local] + [l.sock for l in links]
            readable, _, _ = select.select(socks, [], [], 0.005)
            now = time.time()
            if now - last_apply >= 0.1:
                last_apply = now
                for l in links:
                    l.apply(now)
            if now - last_ping >= bc.PING_S:
                last_ping = now
                for l in links:
                    l.ping(sid, now)
            for s in readable:
                if s is local:
                    for _ in range(64):
                        try:
                            payload, addr = local.recvfrom(2048)
                        except BlockingIOError:
                            break
                        libsrt_addr = addr
                        self.obs.sent(payload, now)
                        alive = [l for l in links if l.state(now) != "mort"] or links
                        usable = [l for l in alive if l.state(now) in ("ok", "dégradé", "encombré")] or alive
                        if bc.audio_only(payload):
                            targets = alive
                            counters["audio"] += 1
                        elif bc.retransmitted(payload):
                            targets = [min(usable, key=bc.Link.score)]
                            counters["retransmis"] += 1
                        else:
                            healthy = [l for l in usable if l.state(now) in ("ok", "encombré")] or usable
                            cands = [l for l in healthy if l.effective_share(now) > 0] or healthy
                            cands = [l for l in cands if l.rate_kbps(now) < l.cap_kbps] or cands
                            targets = [min(cands, key=lambda l: l.sent_bytes / max(l.effective_share(now), 1e-6))]
                            counters["video"] += 1
                        seq = bc.data_seq(payload)
                        for l in targets:
                            pkt = bc.HDR.pack(bc.MAGIC, bc.T_SRT, l.id, sid) + payload
                            l.sent_bytes += len(payload)
                            l.impaired_send(pkt, now)
                            if len(targets) == 1:
                                l.remember(payload, now)
                                if seq is not None:
                                    l.data_sent.append(now)
                                    seq_link[seq] = l
                                    if len(seq_link) > 20000:
                                        for k in list(seq_link)[:10000]:
                                            del seq_link[k]
                    continue
                l = next(x for x in links if x.sock is s)
                for _ in range(64):
                    try:
                        pkt, _ = s.recvfrom(2048)
                    except (BlockingIOError, ConnectionResetError):
                        break
                    if len(pkt) < bc.HDR.size or pkt[:2] != bc.MAGIC:
                        continue
                    _, typ, _, psid = bc.HDR.unpack_from(pkt)
                    payload = pkt[bc.HDR.size:]
                    if typ == bc.T_PONG:
                        l.pong(payload, now)
                    elif typ == bc.T_SRT and libsrt_addr is not None:
                        h = hash(payload)
                        if now - seen_at.get(h, 0.0) < 0.3:
                            counters["retour dupliqué"] += 1
                            continue
                        seen_at[h] = now
                        if len(seen_at) > 512:
                            for k in [k for k, t in seen_at.items() if now - t > 0.3]:
                                del seen_at[k]
                        counters["retour"] += 1
                        self.obs.returned(payload)
                        for lost in bc.nak_seqs(payload):
                            owner = seq_link.pop(lost, None)
                            if owner is not None:
                                owner.data_lost.append(now)
                                counters["pertes annoncées"] += 1
                        try:
                            local.sendto(payload, libsrt_addr)
                        except OSError:
                            pass
            # liens suspects : rejeu de la dernière seconde sur un autre lien (comme bondclient / LinkMux)
            for l in links:
                if l.just_fell:
                    l.just_fell = False
                    others = [o for o in links if o is not l and o.state(now) in ("ok", "dégradé", "encombré")]
                    if others and l.recent:
                        o = min(others, key=bc.Link.score)
                        for _, payload in list(l.recent):
                            o.impaired_send(bc.HDR.pack(bc.MAGIC, bc.T_SRT, o.id, sid) + payload, now)
                        counters["rejoués"] += len(l.recent)
                        l.recent.clear()
            for l in links:
                l.flush(now)
            if now - last_stats >= 0.5:
                last_stats = now
                buf = self.obs.buffer_ms(now)
                st = phone.state()
                phone.on_stats(now, buf, self.obs.dropped, self.video_kbps(now) + 90, True)
                self.t_state[st] += 0.5
                self.video_kbps_samples.append(self.video_kbps(now))
        self.links, self.counters = links, counters

    def run(self):
        threading.Thread(target=self.emitter, daemon=True).start()
        time.sleep(0.3)
        self.start_ffmpeg()
        mux = threading.Thread(target=self.mux_loop, daemon=True)
        mux.start()
        t0 = time.time()
        self.t0 = t0
        last = 0.0
        while time.time() - t0 < self.a.seconds:
            time.sleep(0.5)
            if self.ff.poll() is not None:
                log("ffmpeg terminé prématurément")
                break
            if time.time() - last >= 10 and not self.a.quiet:
                last = time.time()
                ph = self.phone
                log(f"cible {ph.target} kb/s, vidéo {self.video_kbps(time.time())} kb/s, tampon {self.obs.buffer_ms(time.time())} ms, {ph.state()}, "
                    + " | ".join(f"{l.name} {l.state(time.time())} RTT {l.rtt_ms:.0f} ms {l.rate_kbps(time.time()):.0f} kb/s" for l in getattr(self, 'links', [])))
        self.running = False
        try:
            self.ff.kill()
        except Exception:
            pass
        mux.join(2)
        return self.result()

    def result(self):
        links = getattr(self, "links", [])
        tot = sum(l.sent_bytes for l in links) or 1
        return {
            "seconds": self.a.seconds,
            "links": [{"name": l.name, "bytes": l.sent_bytes, "share": round(l.sent_bytes / tot, 3), "target_share": round(l.share, 3)} for l in links],
            "payload_bytes": self.video_total + self.audio_total,
            "sent_bytes": tot,
            "overhead": round(tot / max(self.video_total + self.audio_total, 1), 3),
            "retransmitted": self.counters.get("retransmis", 0) if hasattr(self, "counters") else 0,
            "replayed": self.counters.get("rejoués", 0) if hasattr(self, "counters") else 0,
            "video_kbps_avg": round(sum(self.video_kbps_samples) / max(len(self.video_kbps_samples), 1)),
            "video_kbps_min": min(self.video_kbps_samples) if self.video_kbps_samples else 0,
            "seconds_by_state": {k: round(v, 1) for k, v in self.t_state.items()},
            "ladder_switches": self.switches,
            "srt_dropped": self.obs.dropped,
            "events": [(round(t - self.t0, 1), e) for t, e in self.phone.events][-80:],
            "timeline": [(round(t - self.t0, 1), k, b, st) for t, k, b, st in self.phone.timeline][::4],
        }


def parse_args(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", required=True, help="fichier JSON du scénario")
    ap.add_argument("--vps", default="127.0.0.1", help="hôte du bond (réplique locale par défaut)")
    ap.add_argument("--path", default="turboirl-bench1", help="chemin MediaMTX (turboirl-benchN)")
    ap.add_argument("--ports", type=int, default=9100, help="ports UDP locaux (TS, puis répartiteur = +1)")
    ap.add_argument("--seconds", type=int, default=120)
    ap.add_argument("--token", default="", help="jeton d'écriture (défaut : ~/.turboirl-vps.env ou /mnt/c/Users/Fonias/.turboirl-vps.env)")
    ap.add_argument("--out", default="", help="résultat JSON")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args(argv)
    if not a.token:
        for p in (os.path.expanduser("~/.turboirl-vps.env"), "/mnt/c/Users/Fonias/.turboirl-vps.env"):
            if os.path.exists(p):
                for line in open(p, encoding="utf-8"):
                    if line.startswith("WRITE_TOKEN="):
                        a.token = line.split("=", 1)[1].strip()
                break
    if not a.token:
        sys.exit("jeton d'écriture introuvable")
    return a


def main(argv=None):
    a = parse_args(argv)
    scenario = json.load(open(a.scenario, encoding="utf-8"))
    sim = Sim(a, scenario)
    res = sim.run()
    res["scenario"] = os.path.basename(a.scenario)
    print("BILAN " + json.dumps({k: v for k, v in res.items() if k not in ("events", "timeline")}, ensure_ascii=False), flush=True)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump(res, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
