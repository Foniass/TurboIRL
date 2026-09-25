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
import calendar
import os
import re
import socket
import subprocess
import sys
import threading
import time
from collections import deque

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from obs import Obs  # noqa: E402
import json  # noqa: E402
import urllib.request  # noqa: E402

DEFAULT_VPS_URL = "https://turboirl.mathisjacqueline.com"


DEFAULT_RELAY_HOST = "turboirl.mathisjacqueline.com"


RELAY_PATHS = ("turboirlb", "turboirl")   # flux fusionné (appli ≥ 2.5, via bond) puis publication directe (appli ≤ 2.4)


def relay_source(a, token, attempt=0):
    """Lecture du flux sur MediaMTX (VPS) : SRT en appelant, identifié par le jeton de lecture ; les deux chemins
    possibles sont essayés à tour de rôle."""
    path = RELAY_PATHS[attempt % len(RELAY_PATHS)]
    return f"srt://{a.relay_host}:{a.relay_port}?streamid=read:{path}:reader:{token}&latency={a.relay_latency_ms * 1000}"


def vps_read_token():
    """Jeton de lecture de l'API du VPS : ~/.turboirl-vps.env (READ_TOKEN=…) ou TURBOIRL_READ_TOKEN."""
    t = os.environ.get("TURBOIRL_READ_TOKEN")
    if t:
        return t
    try:
        for line in open(os.path.join(os.path.expanduser("~"), ".turboirl-vps.env"), encoding="utf-8"):
            if line.startswith("READ_TOKEN="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


class ObsControl:
    """Le téléphone commande le stream OBS à travers l'API du VPS : le récepteur relève la commande (start/stop)
    toutes les 3 s, l'exécute sur OBS par WebSocket, l'acquitte, et publie l'état d'OBS toutes les 5 s (ouvert,
    en direct, durée, débit sortant). OBS n'est jamais exposé : le PC ne fait que tirer sur l'API."""

    def __init__(self, base_url, token, dry_run=False, device="", version="", receiver=None):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.dry_run = dry_run
        self.device = device
        self.version = version
        self.receiver = receiver
        self.last_status = {}      # dernier état envoyé au VPS (lu par la fenêtre du logiciel PC)
        self.orders = None         # état des commandes de livraison (API du VPS), affiché dans OBS
        self.orders_offset = 0.0   # horloge du serveur − horloge du PC, d'après le champ now de l'API
        self.last_command = ""     # dernière commande du téléphone et son résultat
        self.api_ok = None         # None = jamais joint, True/False = dernier appel
        self.last_id = 0
        self.last_bytes = None
        self.last_bytes_at = 0.0
        self.failures = 0
        self.obs = None          # connexion WebSocket OBS gardée ouverte (sinon OBS journalise une connexion toutes les 3 s)
        self.obs_lock = threading.Lock()

    def obs_request(self, request_type, data=None):
        """Requête OBS sur une connexion persistante ; rouverte si OBS a été fermé ou relancé."""
        with self.obs_lock:
            for attempt in (1, 2):
                try:
                    if self.obs is None:
                        self.obs = Obs()
                    return self.obs.request(request_type, data)
                except Exception:
                    self.obs = None
                    if attempt == 2:
                        raise

    def start(self):
        threading.Thread(target=self.loop, daemon=True).start()
        threading.Thread(target=self.status_loop, daemon=True).start()
        threading.Thread(target=self.orders_loop, daemon=True).start()
        log(f"commande OBS : à l'écoute de {self.base_url}" + (" (simulation, sans lancer le stream)" if self.dry_run else ""))

    def api(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=35) as r:
            return json.loads(r.read().decode("utf-8") or "{}")

    def loop(self):
        """Attente longue : l'API retient la requête jusqu'à 25 s et répond dès qu'une commande est déposée."""
        while True:
            try:
                cmd = self.api("GET", f"/api/turboirl/command?after={self.last_id}&wait=25")
                if cmd.get("id"):
                    self.last_id = cmd["id"]
                    result = self.execute(cmd.get("action"))
                    self.last_command = f"{cmd.get('action')} ({time.strftime('%H:%M:%S')}) : {result}"
                    log(f"commande OBS #{cmd['id']} {cmd.get('action')} (de {cmd.get('device', '?')}) : {result}")
                    self.api("POST", "/api/turboirl/command/ack", {"id": cmd["id"], "result": result})
                    self.api("POST", "/api/turboirl/obs", self.status())
                self.failures = 0
                self.api_ok = True
            except Exception as e:
                self.api_ok = False
                self.failures += 1
                if self.failures in (1, 20, 200):  # au 1er échec, puis de loin en loin
                    log(f"commande OBS : API injoignable ({e})")
                time.sleep(3)

    def status_loop(self):
        while True:
            try:
                self.api("POST", "/api/turboirl/obs", self.status())
            except Exception:
                pass
            time.sleep(3)

    def set_orders(self, st):
        self.orders = st
        now = iso_epoch(st.get("now") or "") if st else None
        if now is not None:
            self.orders_offset = now - time.time()

    def orders_loop(self):
        """Commandes : état + historique daté relevés chaque seconde, textes OBS décalés du délai vidéo.
        Les textes sont recalculés 4 fois par seconde : à une fois par seconde (plus la durée de la requête), le
        chrono sautait des secondes, OBS ne relisant les fichiers qu'environ chaque seconde lui aussi."""
        n = 0
        while True:
            if n % 4 == 0:
                try:
                    self.set_orders(self.api("GET", "/api/turboirl/orders"))
                except Exception:
                    pass
            try:
                ov = self.receiver.overlay if self.receiver is not None else None
                if ov is not None:
                    ov.apply_orders(self.orders, time.time() + self.orders_offset)
            except Exception:
                pass
            n += 1
            time.sleep(0.25)

    def post_orders(self, body):
        """Depuis la fenêtre du logiciel PC : effacer, régler l'objectif."""
        self.set_orders(self.api("POST", "/api/turboirl/orders", body))
        return self.orders

    def execute(self, action):
        if action not in ("start", "stop"):
            return "action inconnue"
        try:
            active = self.obs_request("GetStreamStatus").get("outputActive", False)
        except Exception as e:
            return f"OBS fermé ou WebSocket inactif ({e})"
        try:
            if action == "start":
                if active:
                    return "déjà en direct"
                if self.dry_run:
                    return "stream lancé (simulation)"
                try:
                    self.obs_request("SetCurrentProgramScene", {"sceneName": SCENE_NAME})
                except Exception as e:
                    log(f"OBS : impossible de passer sur la scène « {SCENE_NAME} » ({e})")
                ov = self.receiver.overlay if self.receiver is not None else None
                if ov is not None and ov.obs is not None:
                    try:
                        ov.set_shown(False)  # jamais de message de coupure hérité au lancement d'une diffusion
                    except Exception:
                        pass
                self.obs_request("StartStream")
                # OBS accepte la demande même si la sortie échoue aussitôt (clé ou service absents, encodeur en
                # erreur) : on vérifie que le stream tourne vraiment avant de dire « lancé »
                for _ in range(6):
                    time.sleep(0.5)
                    if self.obs_request("GetStreamStatus").get("outputActive", False):
                        return "stream lancé"
                return "OBS n'a pas pu démarrer le stream (service / clé de stream réglés dans OBS ?)"
            if not active:
                return "déjà arrêté"
            if self.dry_run:
                return "stream arrêté (simulation)"
            self.obs_request("StopStream")
            return "stream arrêté"
        except Exception as e:
            return f"refusé par OBS ({e})"

    def status(self):
        st = self._status()
        st.update({"device": self.device, "version": self.version,
                   "receiving": bool(self.receiver is not None and self.receiver.decoder_connected)})
        self.last_status = st
        return st

    def _status(self):
        try:
            st = self.obs_request("GetStreamStatus")
        except Exception:
            self.last_bytes = None
            return {"obsOpen": False, "streaming": False, "timecode": "", "kbps": 0}
        now = time.time()
        kbps = 0
        b = st.get("outputBytes", 0)
        if self.last_bytes is not None and now > self.last_bytes_at and b >= self.last_bytes:
            kbps = (b - self.last_bytes) * 8 / (now - self.last_bytes_at) / 1000
        self.last_bytes, self.last_bytes_at = b, now
        return {"obsOpen": True, "streaming": bool(st.get("outputActive")), "timecode": st.get("outputTimecode", "")[:8], "kbps": kbps}

TS_PACKET = 188
SCENE_NAME = "TurboIRL"          # scène OBS dédiée : créée si absente, le reste de l'OBS n'est jamais touché
# Textes des commandes de livraison (créés s'ils manquent, contenu et visibilité pilotés ici, style et position libres)
ORDER_TEXTS = [
    ("TurboIRL commandes", "0", 24.0, 90.0, True),
    ("TurboIRL total", "0 €", 24.0, 140.0, True),
    ("TurboIRL commande titre", "Commande #1", 24.0, 200.0, False),
    ("TurboIRL commande prix", "0 €", 24.0, 250.0, False),
    ("TurboIRL commande temps", "00:00", 24.0, 300.0, False),
]
CURRENT_TEXTS = ("TurboIRL commande titre", "TurboIRL commande prix", "TurboIRL commande temps")
SHOW_DELAY_S = 1.5   # OBS relit les fichiers environ chaque seconde : on n'affiche qu'une fois le nouveau contenu lu
time.strptime("2000-01-01", "%Y-%m-%d")  # charge _strptime une fois (premier appel non sûr entre threads)


def iso_epoch(s):
    """Date ISO de l'API (UTC, « 2026-09-25T20:19:17.042Z ») → secondes epoch, ou None."""
    try:
        t = calendar.timegm(time.strptime(s[:19], "%Y-%m-%dT%H:%M:%S"))
        frac = s[19:].rstrip("Z")
        return t + (float(frac) if frac.startswith(".") else 0.0)
    except Exception:
        return None


def state_at(history, t, fallback):
    """État des commandes en vigueur à l'instant t (heure du serveur) d'après l'historique daté de l'API."""
    best = None
    for h in history:
        at = iso_epoch(h.get("at") or "")
        if at is not None and at <= t:
            best = h
    if best is None:
        best = history[0] if history else fallback
    return best


def euros(v):
    return ("%.2f" % float(v)).replace(".", ",").replace(",00", "") + " €"


# Les textes des commandes changent chaque seconde (durée) : ils sont écrits dans des fichiers qu'OBS lit lui-même
# (« lire depuis un fichier »), jamais poussés par WebSocket — 25/09 : SetInputSettings chaque seconde figeait puis
# faisait planter OBS 32 (source texte GDI+). Le WebSocket ne sert plus qu'à la visibilité, rare.
OBS_TEXT_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "TurboIRL", "obs")


def text_file(name):
    return os.path.join(OBS_TEXT_DIR, name.replace("TurboIRL ", "").replace(" ", "_") + ".txt")


def write_text_file(name, text):
    os.makedirs(OBS_TEXT_DIR, exist_ok=True)
    p = text_file(name)
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, p)
MEDIA_NAME = "TurboIRL flux"     # source média qui lit le récepteur (une source existante sur la même URL est adoptée)


LOG_LISTENERS = []   # fonctions appelées avec chaque ligne (fenêtre du logiciel PC, journal vers le VPS)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # ffmpeg sans console quand le logiciel PC est une fenêtre


def log(msg):
    line = time.strftime("[%H:%M:%S] ") + msg
    if sys.stdout is not None:
        print(line, flush=True)
    for fn in LOG_LISTENERS:
        try:
            fn(line)
        except Exception:
            pass


def listener(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(1)
    return s


class ObsOverlay:
    """Affiche/masque une source OBS (texte « TurboIRL coupure ») quand le viewer voit une image figée.

    Le gel est jugé à la sortie du répéteur, c'est-à-dire ce que le viewer voit vraiment : les 12 s de tampon SRT
    sont déjà passées. Une coupure 5G ou un changement de batterie GoPro donnent le même symptôme (plus aucune
    nouvelle image), donc un seul message. La source est cherchée dans les scènes ; si elle n'existe pas, elle
    est créée dans la scène courante avec un texte par défaut. Ensuite seule sa visibilité est pilotée : texte,
    police, position, animations se règlent librement dans OBS.
    """

    def __init__(self, receiver, source, text, freeze_s):
        self.r = receiver
        self.source = source
        self.text = text
        self.freeze_s = freeze_s
        self.obs = None
        self.item = None       # (nom de scène, id de l'élément) où la source est posée
        self.shown = None      # état envoyé à OBS (None = inconnu)
        self.retry_at = 0.0
        self.media_name = None
        self.last_error = ""
        self.texts = {}          # nom de source texte → id d'élément dans la scène
        self.text_state = {}     # nom → (texte, visible) déjà envoyés à OBS
        self.phone_latency_ms = 0   # tampon SRT du téléphone, transmis avec chaque commande (0 = inconnu → 12 s)
        self.order_delay_s = 0.0    # décalage appliqué aux textes des commandes (délai GoPro → OBS estimé)
        # la connexion OBS est utilisée par le thread du gel et par celui des commandes : jamais deux requêtes à la
        # fois (25/09 : trames entremêlées, client bloqué, reconnexions en boucle qui figeaient OBS)
        self.lock = threading.RLock()

    def start(self):
        threading.Thread(target=self.loop, daemon=True).start()

    def find_item(self, obs):
        """(scène, id) de la source : scène courante d'abord, puis toutes les scènes ; None si absente."""
        scenes = [obs.request("GetCurrentProgramScene").get("currentProgramSceneName")]
        scenes += [sc["sceneName"] for sc in obs.request("GetSceneList").get("scenes", []) if sc["sceneName"] not in scenes]
        for scene in scenes:
            if not scene:
                continue
            try:
                r = obs.request("GetSceneItemId", {"sceneName": scene, "sourceName": self.source})
                return scene, r["sceneItemId"]
            except RuntimeError:
                continue
        return None

    def connect(self):
        """Vérifie (et répare) la scène TurboIRL : la scène, la source média sur le récepteur, le message de coupure."""
        obs = Obs()
        fixed = []
        scenes = [sc["sceneName"] for sc in obs.request("GetSceneList").get("scenes", [])]
        if SCENE_NAME not in scenes:
            obs.request("CreateScene", {"sceneName": SCENE_NAME})
            fixed.append("scène créée")
        fixed += self.ensure_media(obs)
        item, changes = self.ensure_text(obs)
        fixed += changes
        self.texts = {}
        self.text_state = {}
        for name, default, x, y, shown in ORDER_TEXTS:
            item_id, ch = self.ensure_text_source(obs, name, default, x, y, shown, from_file=True)
            self.texts[name] = item_id
            fixed += ch
        self.obs = obs
        self.item = item
        self.shown = None
        self.set_shown(False)  # le message a pu rester affiché après un arrêt brutal
        log(f"OBS : scène « {SCENE_NAME} » vérifiée" + (f" ({', '.join(fixed)})" if fixed else " (flux et message en place)"))

    def ensure_media(self, obs):
        """Source média « TurboIRL flux » (ou une source existante sur la même URL) dans la scène, réglée comme il faut."""
        url = f"udp://127.0.0.1:{self.r.a.obs_port}"
        wanted = {"is_local_file": False, "input": url, "input_format": "mpegts", "buffering_mb": 1,
                  "reconnect_delay_sec": 1, "restart_on_activate": False, "clear_on_media_end": False,
                  "close_when_inactive": False, "hw_decode": True}
        fixed = []
        name = None
        for inp in obs.request("GetInputList", {"inputKind": "ffmpeg_source"}).get("inputs", []):
            n = inp["inputName"]
            settings = obs.request("GetInputSettings", {"inputName": n}).get("inputSettings", {})
            if n == MEDIA_NAME or settings.get("input") == url:
                name = n
                break
        if name is None:
            r = obs.request("CreateInput", {"sceneName": SCENE_NAME, "inputName": MEDIA_NAME, "inputKind": "ffmpeg_source",
                                            "inputSettings": wanted})
            name, item_id = MEDIA_NAME, r["sceneItemId"]
            fixed.append("source média créée")
        else:
            try:
                item_id = obs.request("GetSceneItemId", {"sceneName": SCENE_NAME, "sourceName": name})["sceneItemId"]
            except RuntimeError:
                item_id = obs.request("CreateSceneItem", {"sceneName": SCENE_NAME, "sourceName": name})["sceneItemId"]
                fixed.append("source média ajoutée à la scène")
            cur = obs.request("GetInputSettings", {"inputName": name}).get("inputSettings", {})
            diff = {k: v for k, v in wanted.items() if cur.get(k) != v}
            if diff:
                obs.request("SetInputSettings", {"inputName": name, "inputSettings": diff, "overlay": True})
                fixed.append("réglages du flux corrigés (" + ", ".join(sorted(diff)) + ")")
        # plein cadre en gardant les proportions, tout en bas de la scène
        video = obs.request("GetVideoSettings")
        w, h = float(video.get("baseWidth", 1920)), float(video.get("baseHeight", 1080))
        t = obs.request("GetSceneItemTransform", {"sceneName": SCENE_NAME, "sceneItemId": item_id}).get("sceneItemTransform", {})
        want_t = {"positionX": 0.0, "positionY": 0.0, "boundsType": "OBS_BOUNDS_SCALE_INNER", "boundsAlignment": 0,
                  "boundsWidth": w, "boundsHeight": h}
        if any(abs(float(t.get(k, -1)) - v) > 0.5 if isinstance(v, float) else t.get(k) != v for k, v in want_t.items()):
            obs.request("SetSceneItemTransform", {"sceneName": SCENE_NAME, "sceneItemId": item_id, "sceneItemTransform": want_t})
            fixed.append("cadrage du flux corrigé")
        if not obs.request("GetSceneItemEnabled", {"sceneName": SCENE_NAME, "sceneItemId": item_id}).get("sceneItemEnabled", True):
            obs.request("SetSceneItemEnabled", {"sceneName": SCENE_NAME, "sceneItemId": item_id, "sceneItemEnabled": True})
            fixed.append("flux réaffiché")
        obs.request("SetSceneItemIndex", {"sceneName": SCENE_NAME, "sceneItemId": item_id, "sceneItemIndex": 0})
        self.media_name = name
        return fixed

    def ensure_text(self, obs):
        """Message de coupure dans la scène TurboIRL, masqué, au-dessus du flux ; texte et style restent libres."""
        item_id, fixed = self.ensure_text_source(obs, self.source, self.text, 24.0, 24.0, False, label="message de coupure")
        return (SCENE_NAME, item_id), fixed

    def ensure_text_source(self, obs, name, default_text, x, y, shown, label=None, from_file=False):
        """Une source texte dans la scène TurboIRL : créée si absente (style par défaut lisible), ajoutée à la scène si
        elle existe ailleurs, texte remis si vide, replacée si hors cadre, au-dessus du flux. Style et position libres.
        from_file : le contenu vient d'un fichier écrit par ce programme (textes des commandes)."""
        label = label or name
        fixed = []
        if from_file:
            write_text_file(name, default_text)
        try:
            item_id = obs.request("GetSceneItemId", {"sceneName": SCENE_NAME, "sourceName": name})["sceneItemId"]
        except RuntimeError:
            exists = any(i["inputName"] == name for i in obs.request("GetInputList").get("inputs", []))
            if exists:
                item_id = obs.request("CreateSceneItem", {"sceneName": SCENE_NAME, "sourceName": name,
                                                          "sceneItemEnabled": shown})["sceneItemId"]
                fixed.append(f"{label} ajouté à la scène")
            else:
                kinds = obs.request("GetInputKindList").get("inputKinds", [])
                kind = next((k for k in ("text_gdiplus_v3", "text_gdiplus_v2", "text_gdiplus", "text_ft2_source_v2") if k in kinds), None)
                if kind is None:
                    raise RuntimeError("aucune source texte disponible dans cet OBS")
                settings = {"text": default_text, "font": {"face": "Segoe UI", "size": 40, "style": "Bold", "flags": 1},
                            "color": 0xFFFFFFFF, "outline": True, "outline_color": 0xFF000000, "outline_size": 6, "outline_opacity": 100}
                if from_file:
                    settings.update(self.file_settings(kind, name))
                item_id = obs.request("CreateInput", {"sceneName": SCENE_NAME, "inputName": name, "inputKind": kind,
                                                      "inputSettings": settings, "sceneItemEnabled": shown})["sceneItemId"]
                fixed.append(f"{label} créé")
            obs.request("SetSceneItemTransform", {"sceneName": SCENE_NAME, "sceneItemId": item_id,
                                                  "sceneItemTransform": {"positionX": x, "positionY": y}})
        cur = obs.request("GetInputSettings", {"inputName": name})
        settings_now = cur.get("inputSettings", {})
        if from_file:
            want = self.file_settings(cur.get("inputKind", "text_gdiplus_v3"), name)
            if any(settings_now.get(k) != v for k, v in want.items()):
                obs.request("SetInputSettings", {"inputName": name, "inputSettings": want, "overlay": True})
                fixed.append(f"{label} : lecture depuis fichier")
        elif not str(settings_now.get("text", "")).strip():
            obs.request("SetInputSettings", {"inputName": name, "inputSettings": {"text": default_text}, "overlay": True})
            fixed.append(f"{label} : texte remis")
        video = obs.request("GetVideoSettings")
        t = obs.request("GetSceneItemTransform", {"sceneName": SCENE_NAME, "sceneItemId": item_id}).get("sceneItemTransform", {})
        if not (0 <= float(t.get("positionX", 0)) < float(video.get("baseWidth", 1920)) and 0 <= float(t.get("positionY", 0)) < float(video.get("baseHeight", 1080))):
            obs.request("SetSceneItemTransform", {"sceneName": SCENE_NAME, "sceneItemId": item_id,
                                                  "sceneItemTransform": {"positionX": x, "positionY": y}})
            fixed.append(f"{label} replacé")
        n = len(obs.request("GetSceneItemList", {"sceneName": SCENE_NAME}).get("sceneItems", []))
        obs.request("SetSceneItemIndex", {"sceneName": SCENE_NAME, "sceneItemId": item_id, "sceneItemIndex": max(n - 1, 0)})
        return item_id, fixed

    @staticmethod
    def file_settings(kind, name):
        """Réglages « lire depuis un fichier » selon le type de source texte."""
        path = text_file(name)
        if kind.startswith("text_ft2"):
            return {"from_file": True, "text_file": path}
        return {"read_from_file": True, "file": path}

    def set_text(self, name, text, shown):
        """Contenu (fichier lu par OBS) et visibilité (WebSocket) d'un texte des commandes, seulement s'ils changent."""
        with self.lock:
            obs = self.obs
            item_id = self.texts.get(name)
            if obs is None or item_id is None:
                return
            prev = self.text_state.get(name)
            if prev == (text, shown):
                return
            if prev is None or prev[0] != text:
                write_text_file(name, text)
            if prev is None or prev[1] != shown:
                obs.request("SetSceneItemEnabled", {"sceneName": SCENE_NAME, "sceneItemId": item_id, "sceneItemEnabled": shown})
            self.text_state[name] = (text, shown)

    def order_delay(self):
        """Délai GoPro → OBS estimé, appliqué aux textes des commandes pour qu'ils changent avec l'image de l'appui :
        tampon SRT téléphone → VPS (livraison à heure fixe), latence SRT VPS → PC, son en réserve dans le répéteur
        (mesuré), plus une constante pour la GoPro, le transcodage du téléphone et la source média d'OBS."""
        r = self.r
        phone = (self.phone_latency_ms or 12000) / 1000
        relay = r.a.relay_latency_ms / 1000 if r.a.relay_token else 0.0
        queued = r.fifo_len / (r.rate * r.bps) if r.audio_started else r.a.prefill_ms / 1000
        return phone + relay + queued + r.a.order_extra_ms / 1000

    def apply_orders(self, st, server_now):
        """État des commandes (API du VPS, avec historique daté) → textes OBS, décalés du délai vidéo.

        Le contenu (fichiers relus par OBS) est pris SHOW_DELAY_S en avance sur la visibilité, pour qu'OBS l'ait
        relu au moment où le texte apparaît (sinon l'ancienne commande apparaissait quelques dixièmes de seconde).
        Le chrono se calcule sur l'horloge du serveur : celle du PC n'entre pas en jeu."""
        if self.obs is None or not st:
            return
        with self.lock:
          try:
            history = st.get("history") or []
            self.phone_latency_ms = int(st.get("phoneLatencyMs") or 0) or self.phone_latency_ms
            self.order_delay_s = self.order_delay()
            t_disp = server_now - self.order_delay_s
            visible = state_at(history, t_disp, st)
            content = state_at(history, t_disp + SHOW_DELAY_S, st)
            goal = content.get("goal")
            total = euros(content.get("total", 0))
            if content.get("goalEnabled") and goal:
                total += " / " + euros(goal)
            self.set_text("TurboIRL commandes", str(content.get("count", 0)), True)
            self.set_text("TurboIRL total", total, True)
            vcur = visible.get("current")
            cur = content.get("current") or vcur   # commande qui finit : visible jusqu'au bout de son délai
            if cur:
                t0 = iso_epoch(cur.get("startedAt") or "")
                elapsed = max(0, int(t_disp - t0)) if t0 is not None else 0
                shown = bool(vcur) and vcur.get("id") == cur.get("id")
                self.set_text("TurboIRL commande titre", f"Commande #{cur.get('id', '?')}", shown)
                self.set_text("TurboIRL commande prix", euros(cur.get("price", 0)), shown)
                self.set_text("TurboIRL commande temps", "%02d:%02d" % (elapsed // 60, elapsed % 60), shown)
            else:
                for name in CURRENT_TEXTS:
                    self.set_text(name, "", False)
          except Exception as e:
            log(f"OBS : textes des commandes indisponibles ({e})")
            self.obs = None

    def set_shown(self, shown):
        with self.lock:
            if self.shown == shown or self.obs is None:
                return
            scene, item_id = self.item
            self.obs.request("SetSceneItemEnabled", {"sceneName": scene, "sceneItemId": item_id, "sceneItemEnabled": shown})
            self.shown = shown
        since = time.perf_counter() - self.r.last_new_frame
        log(f"OBS : message de coupure {'affiché' if shown else 'masqué'} ({since:.1f} s depuis la dernière image nouvelle)")

    def loop(self):
        while True:
            try:
              with self.lock:
                if self.obs is None:
                    if time.time() < self.retry_at:
                        time.sleep(1)
                        continue
                    self.connect()
                # affiché seulement pendant un flux du téléphone figé depuis freeze_s ; jamais sans flux (fin de
                # diffusion, attente), sinon il restait collé jusqu'au prochain stream
                frozen = self.r.decoder_connected and self.r.distinct > 0 and time.perf_counter() - self.r.last_new_frame > self.freeze_s
                self.set_shown(frozen)
            except Exception as e:  # OBS fermé, source supprimée… : on réessaie sans bruit toutes les 2 s
                if self.obs is not None or self.retry_at == 0.0:
                    log(f"OBS : incrustation indisponible ({e}), nouvel essai toutes les 2 s")
                self.last_error = str(e)
                self.obs = None
                self.retry_at = time.time() + 2
            time.sleep(0.5)

    def clear(self):
      with self.lock:
        try:
            if self.obs is not None:
                self.set_shown(False)
        except Exception:
            pass


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
        self.slack = int(self.rate * a.slack_ms / 1000) * self.bps
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
        self.last_new_frame = time.perf_counter()  # dernière image nouvelle montrée (gel = rien depuis freeze_s)
        self.pts_lines = 0  # lignes « src=v » lues (doit suivre frames_in ; sinon l'appariement par rang dérive)
        self.audio_last_pts = {}       # session → PTS du dernier bloc audio (détection des trous amont)
        self.audio_gaps = 0
        self.audio_gap_ms = 0.0
        self.decoder_connected = False
        self.fifo_min, self.fifo_max = 1 << 30, 0
        self.fq_min, self.fq_max = 1 << 30, 0
        self.encoder = None
        self.decoder = None
        self.decoder_had_stream = False
        self.overlay = None            # ObsOverlay (scène OBS) une fois run() lancé
        self.control = None            # ObsControl (commande depuis le téléphone)
        self.window = {}               # dernières stats sur 10 s (fenêtre du logiciel PC)
        self.stopping = False

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
        announced = False
        while True:
            self.session += 1
            dump = None
            if self.a.dump_dir:
                os.makedirs(self.a.dump_dir, exist_ok=True)
                dump = os.path.join(self.a.dump_dir, time.strftime("dump-%Y%m%d-%H%M%S.ts"))
            # Sur le relais, le décodeur est un appelant SRT : sans flux du téléphone, MediaMTX refuse la connexion
            # et ffmpeg s'arrête aussitôt ; on réessaie toutes les 2 s sans remplir le journal
            if not announced:
                log(f"session {self.session} : attente du téléphone..." + (f" (dump : {dump})" if dump else ""))
                announced = True
            self.decoder_had_stream = False
            if self.stopping:
                return
            if self.a.relay_token:
                self.a.source = relay_source(self.a, self.a.relay_token, self.session)  # chemin alterné à chaque essai
            p = subprocess.Popen(self.decoder_args(dump), stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0,
                                 creationflags=NO_WINDOW)
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
            if self.decoder_had_stream:
                log(f"session {self.session} terminée (téléphone déconnecté), nouvelle session dans 2 s")
                announced = False
            if self.a.once:
                return
            time.sleep(2 if self.decoder_had_stream else 5)  # sans flux : un essai toutes les 5 s suffit (journal du VPS)

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
                # saut d'horodatage entre deux blocs audio : du son perdu en amont (SRT), comblé en silence par
                # aresample=async, invisible sinon dans les compteurs (le son sort continu, mais troué)
                last = self.audio_last_pts.get(session)
                if last is not None and pending - last > 0.06:
                    with self.lock:
                        self.audio_gaps += 1
                        self.audio_gap_ms += (pending - last) * 1000
                self.audio_last_pts[session] = pending
            pending = None

    def read_decoder_log(self, pipe, session):
        for line in iter(pipe.readline, b""):
            text = line.decode("utf-8", errors="replace").rstrip()
            if not text or "Could not find ref with POC" in text or "Error constructing the frame RPS" in text                     or "Skipping invalid undecodable NALU" in text or "Last message repeated" in text                     or "PPS id out of range" in text or "cu_qp_delta" in text                     or (not self.decoder_had_stream and ("Connection rejected" in text or "Error opening input" in text
                                                         or "Connection setup failure" in text or "I/O error" in text
                                                         or "processConnectResponse" in text or "processAsyncConnectRequest" in text)):
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
            self.decoder_had_stream = True
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
                        if len(self.frames) > self.fps * 9:  # ≈ 370 Mo d'images brutes au pire
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
                # file trop haute (rafale absorbée) : on rogne 20 ms une fois par seconde, sans coupure audible ;
                # le délai supplémentaire pris pendant la rafale se résorbe en quelques dizaines de secondes
                self._drop(self.audio_chunk)
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
                    # phase) ; on rattrape en sautant une image dès que la suivante a plus de 1,5 période de retard
                    # (avec 3 périodes, un retard de 100 à 130 ms pris pendant un trou restait pour toujours)
                    if self.frames and t - self.frames[0][1] < 1.5 / self.fps:
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
                # Reprise réelle seulement si l'image est à peu près à l'heure (< 1 s de retard sur le son) : pendant
                # un gel, les doublons d'avant la coupure arrivent avec plusieurs secondes de retard, par paquets (le
                # décodeur ne les produit qu'à l'image suivante), et n'apportent rien de nouveau au viewer ; ils ne
                # doivent pas retirer le message de coupure. Après une coupure, les vraies images ont 0,1 à 0,3 s de retard.
                if t - pts <= 1.0:
                    now_wall = time.perf_counter()
                    if now_wall - self.last_new_frame > 1.0:
                        log(f"image : reprise après {now_wall - self.last_new_frame:.1f} s d'image figée")
                    self.last_new_frame = now_wall
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
        srv_v.settimeout(1.0)
        srv_a.settimeout(1.0)
        silence = bytes(self.audio_chunk)
        while True:
            enc = subprocess.Popen(self.encoder_args(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                   creationflags=NO_WINDOW)
            self.encoder = enc
            threading.Thread(target=self.read_encoder_log, args=(enc.stderr,), daemon=True).start()
            # ffmpeg ouvre et sonde ses entrées l'une après l'autre : la vidéo part dès sa connexion, le son dès la
            # sienne, chacun dans son thread ; aucun tick n'est jamais sauté (ffmpeg horodate par comptage).
            # Les threads de sortie sont liés à CET encodeur (stop) : à sa mort ils s'arrêtent avant la relance,
            # sinon un ancien thread continuait à consommer une image sur deux (motif doublon / saut du 25/09).
            stop = threading.Event()
            socks = []
            tv = ta = None
            try:
                cv = self.accept_while_alive(srv_v, enc)
                if cv is None:
                    raise OSError("encodeur parti avant de se connecter")
                cv.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 8 << 20)
                t0 = time.perf_counter()
                socks.append(cv)
                tv = threading.Thread(target=self.video_out, args=(cv, t0, socks, stop), daemon=True)
                tv.start()
                ca = self.accept_while_alive(srv_a, enc)
                if ca is None:
                    raise OSError("encodeur parti avant de se connecter (son)")
                ca.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 << 20)
                socks.append(ca)
                log(f"encodeur connecté : sortie continue vers udp://127.0.0.1:{self.a.obs_port} (OBS)")
                ta = threading.Thread(target=self.audio_out, args=(ca, t0, socks, silence, stop), daemon=True)
                ta.start()
                enc.wait()
            except OSError as e:
                log(f"encodeur : {e}")
            finally:
                stop.set()
                self._close_all(socks)
                for t in (tv, ta):
                    if t is not None:
                        t.join(3)
                if enc.poll() is None:
                    enc.kill()
            log("encodeur arrêté, relance dans 1 s")
            time.sleep(1)

    @staticmethod
    def accept_while_alive(srv, proc):
        """accept() par tranches d'1 s tant que l'encodeur vit ; None s'il est mort avant de se connecter."""
        while proc.poll() is None:
            try:
                conn, _ = srv.accept()
                return conn
            except socket.timeout:
                continue
        return None

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

    def video_out(self, cv, t0, socks, stop):
        period = 1.0 / self.fps
        next_v = t0
        try:
            while not stop.is_set():
                now = time.perf_counter()
                if now < next_v:
                    time.sleep(next_v - now)
                cv.sendall(self.next_frame())
                self.frames_out += 1
                next_v += period
        except OSError:
            self._close_all(socks)

    def audio_out(self, ca, t0, socks, silence, stop):
        next_a = t0
        try:
            while not stop.is_set():
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
                gaps = f"trous son amont {self.audio_gaps} ({self.audio_gap_ms:.0f} ms)"
                self.window = {"received": d[0], "repeated": d[2], "late": d[4], "skipped": d[5], "silence_ms": d[3] * 20,
                               "fifo_min_ms": self.fifo_min * ms if self.fifo_max else 0, "fifo_max_ms": self.fifo_max * ms,
                               "audio_gaps": self.audio_gaps, "audio_gap_ms": self.audio_gap_ms, "at": time.time()}
                self.audio_gaps, self.audio_gap_ms = 0, 0.0
                self.frame_line_lag = 0.0
                self.fifo_min, self.fifo_max = 1 << 30, 0
                self.fq_min, self.fq_max = 1 << 30, 0
                worst = self.sync_worst
                self.sync_worst = 0.0
            log(
                f"10 s : images reçues {d[0]}, envoyées {d[1]} (répétées {d[2]}, en retard {d[4]}, sautées {d[5]}), "
                f"file image {fq}, file son {fifo}, silence inséré {d[3] * 20} ms, "
                f"son sauté {self.skipped_audio * ms:.0f} ms au total, {gaps}, image en retard sur le son au pire {worst * 1000:.0f} ms, {unpaired}, "
                f"décodeur {'connecté' if self.decoder_connected else 'absent'}"
            )

    def run(self):
        for fn in (self.video_in, self.audio_in, self.output, self.stats):
            threading.Thread(target=fn, daemon=True).start()
        overlay = None
        if self.a.obs_overlay:
            overlay = ObsOverlay(self, self.a.obs_overlay, self.a.overlay_text, self.a.freeze_seconds)
            overlay.start()
        self.overlay = overlay
        if not self.a.no_vps:
            token = self.a.token or vps_read_token()
            if token:
                self.control = ObsControl(self.a.vps_url, token, dry_run=self.a.dry_run_obs, device=self.a.device,
                                          version=self.a.version, receiver=self)
                self.control.start()
            else:
                log("commande OBS désactivée : pas de jeton de lecture (~/.turboirl-vps.env)")
        time.sleep(0.5)
        log(f"récepteur prêt : {self.w}x{self.h} à {self.fps} i/s, son {self.rate} Hz, réserve {self.a.prefill_ms} ms")
        try:
            self.run_decoder_sessions()
            time.sleep(3)  # laisse l'encodeur écouler la fin (relecture)
        finally:
            self.shutdown()

    def shutdown(self):
        """Arrêt propre (fenêtre fermée, Ctrl+C) : message de coupure masqué, ffmpeg tués."""
        self.stopping = True
        if self.overlay is not None:
            self.overlay.clear()
        for p in (self.decoder, self.encoder):
            if p and p.poll() is None:
                p.kill()


def parse_args(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default="", help="entrée ffmpeg ; par défaut le relais SRT du VPS (MediaMTX), sinon "
                    "srt://0.0.0.0:9000?mode=listener&latency=... (téléphone en direct) ou udp://... (relecture)")
    ap.add_argument("--relay-host", default=DEFAULT_RELAY_HOST)
    ap.add_argument("--relay-port", type=int, default=8890)
    ap.add_argument("--relay-latency-ms", type=int, default=1000, help="latence SRT VPS → PC (le gros tampon est côté téléphone → VPS)")
    ap.add_argument("--dump-dir", default="", help="dossier des dumps bruts (vide = pas de dump)")
    ap.add_argument("--obs-port", type=int, default=9001)
    ap.add_argument("--once", action="store_true", help="une seule session de décodeur puis fin (relecture)")
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--fps", type=int, default=30)
    # Réserve de son (= avance du son sur sa lecture, donc marge dont disposent les images pour arriver à l'heure).
    # 24/09 : après un trou d'1 s de la caméra, le décodeur ffmpeg garde 16 images (530 ms) coincées dans ses files
    # pour le reste de la session, en plus des ~300 ms de retard normal des images sur le son ; avec 700 ms de
    # réserve il ne restait plus de marge et une image sur trois arrivait en retard (2 min de saccades). 1200 ms
    # couvre ce cas. --max-ms : au-delà, la file son est sautée d'un coup (jamais atteint par une simple rafale : en
    # direct le téléphone peut livrer 2,5 s de son d'un coup après un blocage Wi-Fi de la caméra, et le sauter
    # coupait le son de 1,8 s) ; --slack-ms : au-dessus de réserve + marge, on rogne 20 ms par seconde.
    # 24/09 soir (relais VPS) : la liaison caméra → téléphone (Wi-Fi du hotspot) se coupe 1 à 2 s de temps en temps,
    # et rien ne peut tamponner ça en amont ; 2500 ms de réserve couvrent ces trous sans silence (délai +1,3 s)
    ap.add_argument("--prefill-ms", type=int, default=2500)
    ap.add_argument("--order-extra-ms", type=int, default=2000,
                    help="part fixe du délai GoPro → OBS (GoPro, transcodage téléphone, source média OBS) pour décaler les textes des commandes")
    ap.add_argument("--max-ms", type=int, default=8000)
    ap.add_argument("--slack-ms", type=int, default=1500)
    ap.add_argument("--obs-overlay", default="TurboIRL coupure",
                    help="source texte OBS à piloter (obs-websocket) ; vide = pas d'incrustation")
    ap.add_argument("--overlay-text", default="Petite coupure, le stream revient dans un instant")
    ap.add_argument("--freeze-seconds", type=float, default=3.0, help="image figée depuis autant de secondes → message")
    ap.add_argument("--vps-url", default=DEFAULT_VPS_URL, help="API du VPS pour la commande OBS depuis le téléphone")
    ap.add_argument("--no-vps", action="store_true", help="ne pas écouter les commandes OBS du téléphone")
    ap.add_argument("--dry-run-obs", action="store_true", help="acquitte les commandes sans vraiment lancer/arrêter le stream OBS")
    ap.add_argument("--in-video", type=int, default=9021)
    ap.add_argument("--in-audio", type=int, default=9022)
    ap.add_argument("--out-video", type=int, default=9031)
    ap.add_argument("--out-audio", type=int, default=9032)
    ap.add_argument("--token", default="", help="jeton du VPS (relais SRT et API) ; défaut : ~/.turboirl-vps.env")
    ap.add_argument("--device", default="", help="nom de ce PC dans l'état publié au VPS")
    ap.add_argument("--version", default="", help="version du logiciel PC publiée au VPS")
    a = ap.parse_args(argv)
    a.relay_token = ""
    if not a.source:
        token = a.token or vps_read_token()
        if not token:
            sys.exit("pas de --source et pas de jeton (~/.turboirl-vps.env ou --token) pour le relais du VPS")
        a.relay_token = token
        a.source = relay_source(a, token)
        log(f"lecture du relais SRT du VPS : srt://{a.relay_host}:{a.relay_port} (latence {a.relay_latency_ms} ms)")
    return a


def main():
    try:
        Receiver(parse_args()).run()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
