"""Client bond de banc d'essai (PC) : reçoit les datagrammes SRT d'un ffmpeg local et les répartit sur N liens
simulés vers le service bond du VPS, avec pannes simulées par lien. Même protocole et même politique que
l'appli (LinkMux.kt) : sert à valider le service et la politique de répartition sans téléphone.

  ffmpeg -re -stream_loop -1 -i bench/turboirl-bench.mp4 -c copy -f mpegts \\
      "srt://127.0.0.1:9040?streamid=publish:turboirl:phone:<jeton>&latency=12000"
  python tools/bondclient.py --link "5G,share=0.6,loss=0.02" --link "wifi,share=0.4,outage=60/8"

Options d'un lien : name,share=0.6,loss=0.05 (proportion de datagrammes perdus),delay=200 (ms ajoutées),
kbps=3000 (plafond de débit, file de 500 ms puis pertes),outage=60/8 (toutes les 60 s, coupure de 8 s),seed=1
"""
import argparse
import collections
import heapq
import random
import select
import socket
import struct
import sys
import time

MAGIC = b"TB"
T_SRT, T_PING, T_PONG, T_HELLO = 0, 1, 2, 3
HDR = struct.Struct("!2sBBI")
PING_S = 0.1            # santé par lien : 3 pings sans réponse = lien suspect (≈ 400 ms), 5 pongs de suite = rétabli
SUSPECT_MISSES = 3
RECOVER_PONGS = 5
REPLAY_S = 1.0          # à la chute d'un lien : la dernière seconde envoyée dessus repart sur les autres
VIDEO_PID, AUDIO_PID = 0x100, 0x101


def log(msg):
    print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


def data_seq(payload):
    """Numéro de séquence d'un paquet de données SRT, None pour un paquet de contrôle."""
    if len(payload) < 16 or payload[0] & 0x80:
        return None
    return struct.unpack_from("!I", payload, 0)[0] & 0x7FFFFFFF


def nak_seqs(payload):
    """Séquences perdues annoncées par un NAK SRT (type de contrôle 3) : liste de numéros ou d'intervalles."""
    if len(payload) < 16 or not payload[0] & 0x80 or struct.unpack_from("!H", payload, 0)[0] & 0x7FFF != 3:
        return []
    out = []
    words = struct.unpack_from("!%dI" % ((len(payload) - 16) // 4), payload, 16)
    i = 0
    while i < len(words):
        w = words[i]
        if w & 0x80000000:  # début d'intervalle, le mot suivant est la fin
            if i + 1 < len(words):
                a, b = w & 0x7FFFFFFF, words[i + 1] & 0x7FFFFFFF
                if 0 <= b - a < 2000:
                    out.extend(range(a, b + 1))
            i += 2
        else:
            out.append(w)
            i += 1
    return out


def retransmitted(payload):
    """Paquet de données SRT réémis (drapeau R) : à router sur le meilleur lien, jamais sur un lien suspect."""
    return len(payload) >= 16 and not payload[0] & 0x80 and bool(payload[4] & 0x04)


def audio_only(payload):
    """Datagramme SRT de données ne portant aucun paquet TS vidéo (son, tables, horloge) → à dupliquer."""
    if len(payload) < 16 or payload[0] & 0x80:
        return False  # contrôle SRT : traité comme la vidéo (petit, sur le lien principal)
    ts = payload[16:]
    if len(ts) % 188:
        return False
    for off in range(0, len(ts), 188):
        if ts[off] != 0x47:
            return False
        pid = ((ts[off + 1] & 0x1F) << 8) | ts[off + 2]
        if pid == VIDEO_PID:
            return False
    return True


class Link:
    def __init__(self, lid, spec, vps):
        parts = spec.split(",")
        self.id = lid
        self.name = parts[0]
        opts = dict(p.split("=", 1) for p in parts[1:])
        self.share = float(opts.get("share", 1.0))
        self.loss = float(opts.get("loss", 0))
        self.delay = float(opts.get("delay", 0)) / 1000
        self.kbps = float(opts.get("kbps", 0))
        o = opts.get("outage")
        self.outage = tuple(float(x) for x in o.split("/")) if o else None
        self.rng = random.Random(int(opts.get("seed", lid + 1)))
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setblocking(False)
        self.vps = vps
        self.sent_bytes = 0
        self.pings = collections.deque()      # (heure d'envoi, reçu ?)
        self.rtt_ms = 0.0
        self.last_pong = 0.0
        self.queue = []                        # (heure de sortie, datagramme) pour le délai simulé
        self.bucket = 0.0                      # jetons (octets) pour le plafond de débit
        self.bucket_at = time.time()
        self.t0 = time.time()
        self.misses = 0            # pings consécutifs sans réponse
        self.streak = 0            # pongs consécutifs
        self.suspect = False
        self.just_fell = False
        self.recent = collections.deque()   # (heure, datagramme SRT) envoyés sur ce lien, pour rejeu à la chute
        self.data_sent = collections.deque()   # (heure) des paquets de données envoyés, fenêtre de 5 s
        self.data_lost = collections.deque()   # (heure) des pertes annoncées par NAK, fenêtre de 5 s

    # ---------------- pannes simulées
    def down(self, now):
        if not self.outage:
            return False
        every, dur = self.outage
        return (now - self.t0) % every >= every - dur

    def impaired_send(self, pkt, now):
        if self.down(now) or (self.loss and self.rng.random() < self.loss):
            return
        if self.kbps:
            self.bucket = min(self.bucket + (now - self.bucket_at) * self.kbps * 125, self.kbps * 125 * 0.5)
            self.bucket_at = now
            if self.bucket < len(pkt):
                return  # file de 500 ms pleine : perdu
            self.bucket -= len(pkt)
        if self.delay:
            heapq.heappush(self.queue, (now + self.delay + self.rng.random() * self.delay * 0.2, pkt))
        else:
            self._raw_send(pkt)

    def flush(self, now):
        while self.queue and self.queue[0][0] <= now:
            self._raw_send(heapq.heappop(self.queue)[1])

    def _raw_send(self, pkt):
        try:
            self.sock.sendto(pkt, self.vps)
        except OSError:
            pass

    # ---------------- santé
    def ping(self, sid, now):
        # le ping précédent est-il resté sans réponse ? (délai laissé : un intervalle, soit ≥ RTT courant)
        if self.pings and not self.pings[-1][1] and now - self.pings[-1][0] >= PING_S - 1e-3:
            self.misses += 1
            self.streak = 0
            if self.misses >= SUSPECT_MISSES and not self.suspect:
                self.suspect = True
                self.just_fell = True
        self.pings.append([now, False])
        while self.pings and now - self.pings[0][0] > 4.0:
            self.pings.popleft()
        self.impaired_send(HDR.pack(MAGIC, T_PING, self.id, sid) + struct.pack("!d", now), now)

    def pong(self, payload, now):
        sent = struct.unpack("!d", payload[:8])[0]
        self.rtt_ms = self.rtt_ms * 0.7 + (now - sent) * 1000 * 0.3 if self.rtt_ms else (now - sent) * 1000
        self.last_pong = now
        for p in self.pings:
            if abs(p[0] - sent) < 1e-6:
                p[1] = True
        self.misses = 0
        self.streak += 1
        if self.suspect and self.streak >= RECOVER_PONGS:
            self.suspect = False

    def remember(self, payload, now):
        self.recent.append((now, payload))
        while self.recent and now - self.recent[0][0] > REPLAY_S:
            self.recent.popleft()

    def loss_pct(self):
        done = [p for p in self.pings if time.time() - p[0] > 1.0]
        if not done:
            return 0.0
        return 100.0 * sum(1 for p in done if not p[1]) / len(done)

    def state(self, now):
        if now - self.last_pong > 5.0:
            return "mort"
        if self.suspect:
            return "suspect"
        if self.loss_pct() > 20 or self.rtt_ms > 1500:
            return "dégradé"
        return "ok"

    def data_loss_pct(self, now):
        while self.data_sent and now - self.data_sent[0] > 5.0:
            self.data_sent.popleft()
        while self.data_lost and now - self.data_lost[0] > 5.0:
            self.data_lost.popleft()
        if len(self.data_sent) < 20:
            return 0.0
        return 100.0 * len(self.data_lost) / len(self.data_sent)

    def effective_share(self, now):
        """Part visée, réduite quand le lien perd des données (SRT le dit par ses NAK) : 5 % de pertes = moitié."""
        loss = self.data_loss_pct(now)
        return self.share * max(0.0, 1.0 - loss / 10.0)

    def score(self):
        """Pour les retransmissions : le lien le plus sûr (pertes récentes, puis RTT)."""
        return (self.loss_pct() + self.data_loss_pct(time.time()), self.rtt_ms)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vps", default="turboirl.mathisjacqueline.com")
    ap.add_argument("--port", type=int, default=8891)
    ap.add_argument("--local", type=int, default=9040, help="port UDP local où ffmpeg/libsrt envoie (srt://127.0.0.1:PORT)")
    ap.add_argument("--link", action="append", required=True, help="spécification d'un lien (voir en-tête)")
    a = ap.parse_args()
    vps = (socket.gethostbyname(a.vps), a.port)
    sid = random.getrandbits(32)
    links = [Link(i, spec, vps) for i, spec in enumerate(a.link)]
    total_share = sum(l.share for l in links) or 1.0
    for l in links:
        l.share /= total_share

    local = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    local.bind(("127.0.0.1", a.local))
    local.setblocking(False)
    libsrt_addr = None
    seen = collections.deque(maxlen=256)
    seen_set = set()
    seq_link = collections.OrderedDict()   # séquence SRT → lien qui l'a portée (fenêtre récente)
    last_ping = 0.0
    last_report = time.time()
    counters = collections.Counter()
    log(f"bond client : session {sid:08x}, {len(links)} lien(s) → {a.vps}:{a.port}, ffmpeg attendu sur srt://127.0.0.1:{a.local}")

    while True:
        now = time.time()
        socks = [local] + [l.sock for l in links]
        readable, _, _ = select.select(socks, [], [], 0.01)
        now = time.time()
        for s in readable:
            if s is local:
                for _ in range(64):
                    try:
                        payload, addr = local.recvfrom(2048)
                    except BlockingIOError:
                        break
                    libsrt_addr = addr
                    alive = [l for l in links if l.state(now) != "mort"] or links
                    usable = [l for l in alive if l.state(now) in ("ok", "dégradé")] or alive
                    if audio_only(payload):
                        targets = alive
                        counters["audio"] += 1
                    elif retransmitted(payload):
                        targets = [min(usable, key=Link.score)]
                        counters["retransmis"] += 1
                    else:
                        healthy = [l for l in usable if l.state(now) == "ok"] or usable
                        # solde : le lien le plus en retard sur sa part (part réduite s'il perd des données) prend le paquet
                        candidates = [l for l in healthy if l.effective_share(now) > 0] or healthy
                        best = min(candidates, key=lambda l: l.sent_bytes / max(l.effective_share(now), 1e-6))
                        targets = [best]
                        counters["video"] += 1
                    seq = data_seq(payload)
                    for l in targets:
                        pkt = HDR.pack(MAGIC, T_SRT, l.id, sid) + payload
                        l.sent_bytes += len(payload)
                        l.impaired_send(pkt, now)
                        if len(targets) == 1:
                            l.remember(payload, now)
                            if seq is not None:
                                l.data_sent.append(now)
                                seq_link[seq] = l
                                if len(seq_link) > 20000:
                                    seq_link.popitem(last=False)
            else:
                l = next(x for x in links if x.sock is s)
                for _ in range(64):
                    try:
                        pkt, _ = s.recvfrom(2048)
                    except BlockingIOError:
                        break
                    if len(pkt) < HDR.size or pkt[:2] != MAGIC:
                        continue
                    _, typ, _, psid = HDR.unpack_from(pkt)
                    payload = pkt[HDR.size:]
                    if typ == T_PONG:
                        l.pong(payload, now)
                    elif typ == T_SRT and libsrt_addr is not None:
                        h = hash(payload)
                        if h in seen_set:
                            counters["retour dupliqué"] += 1
                            continue
                        if len(seen) == seen.maxlen:
                            seen_set.discard(seen[0])
                        seen.append(h)
                        seen_set.add(h)
                        counters["retour"] += 1
                        for lost in nak_seqs(payload):
                            owner = seq_link.pop(lost, None)
                            if owner is not None:
                                owner.data_lost.append(now)
                                counters["pertes annoncées"] += 1
                        local.sendto(payload, libsrt_addr)
        if now - last_ping >= PING_S:
            last_ping = now
            for l in links:
                l.ping(sid, now)
                if l.just_fell:
                    l.just_fell = False
                    others = [o for o in links if o is not l and o.state(now) in ("ok", "dégradé")]
                    if others and l.recent:
                        # ce qui vient de partir sur le lien tombé repart tout de suite ailleurs, sans attendre SRT
                        o = min(others, key=Link.score)
                        for _, payload in l.recent:
                            o.sent_bytes += len(payload)
                            o.impaired_send(HDR.pack(MAGIC, T_SRT, o.id, sid) + payload, now)
                        counters["rejoués"] += len(l.recent)
                        log(f"{l.name} suspect : {len(l.recent)} datagrammes de la dernière seconde rejoués sur {o.name}")
                        l.recent.clear()
        for l in links:
            l.flush(now)
        if now - last_report >= 5.0:
            last_report = now
            tot = sum(l.sent_bytes for l in links) or 1
            parts = []
            for l in links:
                parts.append(f"{l.name} {l.state(now)} RTT {l.rtt_ms:.0f} ms pertes {l.loss_pct():.0f}% (données {l.data_loss_pct(now):.1f}%) "
                             f"{l.sent_bytes / 1e6:.1f} Mo ({100 * l.sent_bytes / tot:.0f}% / cible {100 * l.share:.0f}%)"
                             + (" COUPURE" if l.down(now) else ""))
            log(" | ".join(parts) + f" | son {counters['audio']} vidéo {counters['video']} retransmis {counters['retransmis']} rejoués {counters['rejoués']} retours {counters['retour']} (+{counters['retour dupliqué']} doublons)")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
