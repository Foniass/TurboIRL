"""TurboIRL — logiciel PC : le récepteur (tools/receiver.py) dans une petite fenêtre.

Ce que fait la fenêtre : montrer si tout va bien (version, VPS, OBS, flux du téléphone, stream), garder les
derniers événements, et s'arrêter proprement quand on la ferme. Au lancement, le logiciel compare sa version à la
version du canal choisi sur le VPS (stable pour l'ami, test pour Fonias) et se remplace tout seul si besoin.
Le journal du récepteur est envoyé au VPS toutes les minutes (analyse à distance).

Réglages dans %APPDATA%\\TurboIRL\\config.json : jeton du VPS (le même que dans l'appli), canal, démarrage
automatique. Empaqueté par pc/build.ps1 (PyInstaller, ffmpeg.exe à côté de l'exécutable).
"""
import json
import os
import platform
import queue
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import urllib.request
import zipfile
from tkinter import messagebox, simpledialog, ttk

FROZEN = getattr(sys, "frozen", False)
APP_DIR = os.path.dirname(sys.executable) if FROZEN else os.path.dirname(os.path.abspath(__file__))
RES_DIR = getattr(sys, "_MEIPASS", APP_DIR)
if not FROZEN:
    sys.path.insert(0, os.path.join(os.path.dirname(APP_DIR), "tools"))
else:
    os.environ["PATH"] = APP_DIR + os.pathsep + os.environ.get("PATH", "")  # ffmpeg.exe livré à côté

import receiver  # noqa: E402  (tools/receiver.py)

VPS_URL = receiver.DEFAULT_VPS_URL
CONFIG_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "TurboIRL")
CONFIG_FILE = os.path.join(CONFIG_DIR, "config.json")
STARTUP_LNK = os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "TurboIRL.lnk")


def version():
    try:
        return open(os.path.join(RES_DIR, "VERSION"), encoding="utf-8").read().strip()
    except OSError:
        return "dev"


VERSION = version()
GREEN, ORANGE, RED, GREY = "#2e7d32", "#ef6c00", "#c62828", "#9e9e9e"


def load_config():
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_config(cfg):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)


def api(token, method, path, body=None, timeout=10):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(VPS_URL + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8") or "{}")


def set_autostart(enabled):
    """Raccourci dans le dossier Démarrage de Windows (rien d'autre : pas de tâche planifiée, pas de service)."""
    if enabled:
        target = sys.executable if FROZEN else f'{sys.executable}'
        args = "" if FROZEN else f'"{os.path.abspath(__file__)}"'
        ps = (f"$s = (New-Object -ComObject WScript.Shell).CreateShortcut('{STARTUP_LNK}'); "
              f"$s.TargetPath = '{target}'; $s.Arguments = '{args}'; $s.WorkingDirectory = '{APP_DIR}'; $s.Save()")
        subprocess.run(["powershell", "-NoProfile", "-Command", ps], creationflags=receiver.NO_WINDOW, check=False)
    else:
        try:
            os.remove(STARTUP_LNK)
        except OSError:
            pass


class Updater:
    """Compare la version installée à celle du canal sur le VPS ; télécharge et remplace le dossier si besoin."""

    def __init__(self, token, channel, status_cb):
        self.token = token
        self.channel = channel
        self.status = status_cb

    def target(self):
        info = api(self.token, "GET", "/api/turboirl/release")
        return info.get(self.channel, {}).get("pc", ""), info

    def run(self):
        """True si une mise à jour a été lancée (le programme doit alors se terminer)."""
        try:
            target, info = self.target()
        except Exception as e:
            self.status(f"version : VPS injoignable ({e})", ORANGE)
            return False
        if not target or target == VERSION or not FROZEN:
            self.status(f"version {VERSION} (canal {self.channel} : {target or '?'})", GREEN if target == VERSION else ORANGE)
            return False
        name = f"TurboIRL-PC-{target}.zip"
        if name not in info.get("files", []):
            self.status(f"version {VERSION}, {target} annoncée mais fichier absent du VPS", ORANGE)
            return False
        self.status(f"mise à jour {VERSION} → {target} : téléchargement…", ORANGE)
        tmp = tempfile.mkdtemp(prefix="turboirl-")
        zpath = os.path.join(tmp, name)
        req = urllib.request.Request(VPS_URL + "/api/turboirl/releases/" + name)
        req.add_header("Authorization", "Bearer " + self.token)
        with urllib.request.urlopen(req, timeout=60) as r, open(zpath, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if total:
                    self.status(f"mise à jour {VERSION} → {target} : {done * 100 // total} %", ORANGE)
        new_dir = os.path.join(tmp, "new")
        with zipfile.ZipFile(zpath) as z:
            z.extractall(new_dir)
        inner = os.path.join(new_dir, "TurboIRL")
        if not os.path.isfile(os.path.join(inner, "TurboIRL.exe")):
            self.status("mise à jour : archive inattendue, ignorée", RED)
            return False
        # le remplacement se fait une fois ce programme terminé, par un script PowerShell détaché (UTF-8 avec BOM :
        # un .cmd est lu dans la page de code OEM et un chemin accentué comme C:\Users\Kévin y devenait introuvable)
        # qui relance la nouvelle version
        ps1 = os.path.join(tmp, "update.ps1")
        with open(ps1, "w", encoding="utf-8-sig") as f:
            f.write("Start-Sleep -Seconds 3\n"
                    f"robocopy '{inner}' '{APP_DIR}' /MIR /R:30 /W:1 /NFL /NDL /NJH /NJS | Out-Null\n"
                    f"Start-Process -FilePath '{os.path.join(APP_DIR, 'TurboIRL.exe')}' -WorkingDirectory '{APP_DIR}'\n"
                    f"Remove-Item -Recurse -Force '{tmp}' -ErrorAction SilentlyContinue\n")
        subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", ps1],
                         creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
        self.status(f"mise à jour {target} prête : redémarrage…", ORANGE)
        return True


class Journal:
    """Lignes du journal : file pour la fenêtre, envoi au VPS toutes les 60 s (et à la fermeture)."""

    def __init__(self, token, device):
        self.token = token
        self.device = device
        self.ui = queue.Queue()
        self.pending = []
        self.lock = threading.Lock()
        receiver.LOG_LISTENERS.append(self.on_line)
        threading.Thread(target=self.loop, daemon=True).start()

    def on_line(self, line):
        if " 10 s : " not in line:
            self.ui.put(line)
        with self.lock:
            self.pending.append(line)
            if len(self.pending) > 5000:
                del self.pending[:1000]

    def flush(self, timeout=5):
        with self.lock:
            lines, self.pending = self.pending, []
        if not lines:
            return
        try:
            api(self.token, "POST", "/api/turboirl/receiver",
                {"device": self.device, "version": VERSION, "text": "\n".join(lines) + "\n"}, timeout=timeout)
        except Exception:
            with self.lock:
                self.pending = lines + self.pending

    def loop(self):
        while True:
            time.sleep(60)
            self.flush()


class App:
    def __init__(self, root):
        self.root = root
        self.cfg = load_config()
        self.receiver = None
        self.journal = None
        self.device = platform.node() or "pc"
        root.title(f"TurboIRL — récepteur PC {VERSION}")
        root.resizable(False, False)
        root.protocol("WM_DELETE_WINDOW", self.quit)
        self.build()
        root.after(100, self.start)

    # ---------------------------------------------------------------- fenêtre
    def build(self):
        frame = ttk.Frame(self.root, padding=12)
        frame.grid(sticky="nsew")
        self.rows = {}
        for i, (key, label) in enumerate([("version", "Version"), ("vps", "VPS"), ("obs", "OBS"),
                                          ("phone", "Téléphone"), ("stream", "Stream OBS"), ("orders", "Commandes")]):
            dot = tk.Canvas(frame, width=14, height=14, highlightthickness=0)
            dot.grid(row=i, column=0, padx=(0, 8), pady=3)
            ttk.Label(frame, text=label, width=11).grid(row=i, column=1, sticky="w")
            var = tk.StringVar(value="…")
            ttk.Label(frame, textvariable=var, width=62, anchor="w").grid(row=i, column=2, sticky="w")
            self.rows[key] = (dot, var)
            self.set(key, "…", GREY)
        ttk.Label(frame, text="Derniers événements").grid(row=6, column=0, columnspan=3, sticky="w", pady=(10, 2))
        self.events = tk.Text(frame, height=10, width=90, state="disabled", font=("Consolas", 9), wrap="none")
        self.events.grid(row=7, column=0, columnspan=3, sticky="we")
        bottom = ttk.Frame(frame)
        bottom.grid(row=8, column=0, columnspan=3, sticky="we", pady=(10, 0))
        self.autostart = tk.BooleanVar(value=bool(self.cfg.get("autostart")))
        ttk.Checkbutton(bottom, text="Lancer au démarrage de Windows", variable=self.autostart,
                        command=self.toggle_autostart).pack(side="left")
        ttk.Button(bottom, text="Quitter", command=self.quit).pack(side="right")
        ttk.Button(bottom, text="Réglages…", command=self.settings).pack(side="right", padx=(0, 8))
        ttk.Button(bottom, text="Effacer les commandes", command=self.clear_orders).pack(side="right", padx=(0, 8))

    def set(self, key, text, color):
        dot, var = self.rows[key]
        dot.delete("all")
        dot.create_oval(1, 1, 13, 13, fill=color, outline=color)
        var.set(text)

    def event(self, line):
        self.events.configure(state="normal")
        self.events.insert("end", line + "\n")
        lines = int(self.events.index("end-1c").split(".")[0])
        if lines > 200:
            self.events.delete("1.0", f"{lines - 200}.0")
        self.events.see("end")
        self.events.configure(state="disabled")

    # ---------------------------------------------------------------- démarrage
    def start(self):
        if not self.cfg.get("token"):
            token = simpledialog.askstring("TurboIRL", "Jeton du VPS (le même que dans l'appli du téléphone) :", show="*", parent=self.root)
            if not token:
                self.root.destroy()
                return
            self.cfg["token"] = token.strip()
            self.cfg.setdefault("channel", "stable")
            save_config(self.cfg)
        self.cfg.setdefault("channel", "stable")
        self.journal = Journal(self.cfg["token"], self.device)
        self.root.after(300, self.poll)
        threading.Thread(target=self.boot, daemon=True).start()

    def boot(self):
        upd = Updater(self.cfg["token"], self.cfg["channel"], lambda t, c: self.root.after(0, self.set, "version", t, c))
        try:
            if upd.run():
                time.sleep(1)
                self.root.after(0, self.quit, False)
                return
        except Exception as e:
            self.root.after(0, self.set, "version", f"mise à jour impossible ({e}), version {VERSION} conservée", ORANGE)
        argv = ["--token", self.cfg["token"], "--device", self.device, "--version", VERSION]
        if self.cfg["channel"] == "test":
            dumps = os.path.join(CONFIG_DIR, "dumps")
            os.makedirs(dumps, exist_ok=True)
            argv += ["--dump-dir", dumps]
        try:
            a = receiver.parse_args(argv)
        except SystemExit as e:
            self.root.after(0, self.set, "phone", f"impossible de démarrer : {e}", RED)
            return
        self.receiver = receiver.Receiver(a)
        try:
            self.receiver.run()
        except Exception as e:
            receiver.log(f"récepteur arrêté : {e}")

    # ---------------------------------------------------------------- état
    def poll(self):
        while True:
            try:
                self.event(self.journal.ui.get_nowait())
            except queue.Empty:
                break
        r = self.receiver
        if r is not None:
            ctl, ov = r.control, r.overlay
            if ctl is None or ctl.api_ok is None:
                self.set("vps", "connexion…", GREY)
            elif ctl.api_ok:
                self.set("vps", "joignable" + (f" · {ctl.last_command}" if ctl.last_command else ""), GREEN)
            else:
                self.set("vps", "injoignable (commandes du téléphone et journal en attente)", RED)
            st = ctl.last_status if ctl else {}
            if ov is not None and ov.obs is not None:
                self.set("obs", f"connecté, scène « {receiver.SCENE_NAME} » vérifiée (flux « {ov.media_name} »)", GREEN)
            elif ov is not None and ov.last_error:
                self.set("obs", "OBS fermé ou WebSocket inactif (Outils → Paramètres du serveur WebSocket)", RED)
            else:
                self.set("obs", "connexion…", GREY)
            w = r.window
            if r.decoder_connected:
                frozen = r.distinct > 0 and time.perf_counter() - r.last_new_frame > r.a.freeze_seconds
                fifo = f"réserve son {w.get('fifo_min_ms', 0):.0f}-{w.get('fifo_max_ms', 0):.0f} ms" if w else "réserve son…"
                imgs = f"{w.get('received', 0)} images / 10 s" if w else "images…"
                if frozen:
                    self.set("phone", f"flux reçu mais image figée depuis {time.perf_counter() - r.last_new_frame:.0f} s (message affiché)", ORANGE)
                else:
                    extra = ""
                    if w and (w.get("late") or w.get("silence_ms") or w.get("audio_gap_ms")):
                        extra = f" · retard {w['late']}, silence {w['silence_ms']} ms, trous son {w['audio_gap_ms']:.0f} ms"
                    self.set("phone", f"flux reçu : {imgs}, {fifo}{extra}", GREEN if not extra else ORANGE)
            else:
                self.set("phone", "en attente du téléphone (appuyer sur Démarrer dans l'appli)", GREY)
            o = ctl.orders if ctl else None
            if o:
                goal = o.get("goal")
                total = receiver.euros(o.get("total", 0)) + (f" / {receiver.euros(goal)}" if o.get("goalEnabled") and goal else "")
                cur = o.get("current")
                txt = f"{o.get('count', 0)} finie(s) · total {total}" + (f" · #{cur.get('id')} en cours ({receiver.euros(cur.get('price', 0))})" if cur else "")
                self.set("orders", txt, GREEN if cur else GREY)
            else:
                self.set("orders", "…", GREY)
            if st.get("obsOpen"):
                if st.get("streaming"):
                    self.set("stream", f"EN DIRECT depuis {st.get('timecode', '')} · {st.get('kbps', 0)} kb/s", GREEN)
                else:
                    self.set("stream", "arrêté (bouton « Lancer OBS » dans l'appli du téléphone)", GREY)
            else:
                self.set("stream", "OBS fermé", RED)
        self.root.after(500, self.poll)

    # ---------------------------------------------------------------- réglages, démarrage auto, sortie
    def settings(self):
        win = tk.Toplevel(self.root)
        win.title("Réglages")
        win.resizable(False, False)
        f = ttk.Frame(win, padding=12)
        f.grid()
        ttk.Label(f, text="Jeton du VPS").grid(row=0, column=0, sticky="w")
        token = tk.StringVar(value=self.cfg.get("token", ""))
        ttk.Entry(f, textvariable=token, show="*", width=40).grid(row=0, column=1, pady=3)
        ttk.Label(f, text="Canal de versions").grid(row=1, column=0, sticky="w")
        channel = tk.StringVar(value=self.cfg.get("channel", "stable"))
        rb = ttk.Frame(f)
        rb.grid(row=1, column=1, sticky="w")
        ttk.Radiobutton(rb, text="stable (version validée)", variable=channel, value="stable").pack(side="left")
        ttk.Radiobutton(rb, text="test (dernière publiée)", variable=channel, value="test").pack(side="left", padx=(8, 0))
        ttk.Label(f, text="Jeton et canal s'appliquent au prochain lancement.").grid(row=2, column=0, columnspan=2, pady=(8, 4))
        ctl = self.receiver.control if self.receiver is not None else None
        o = (ctl.orders if ctl else None) or {}
        ttk.Label(f, text="Objectif de thune (€)").grid(row=3, column=0, sticky="w")
        gf = ttk.Frame(f)
        gf.grid(row=3, column=1, sticky="w")
        goal = tk.StringVar(value="" if o.get("goal") is None else str(o.get("goal")).replace(".", ","))
        goal_on = tk.BooleanVar(value=bool(o.get("goalEnabled")))
        ttk.Entry(gf, textvariable=goal, width=10).pack(side="left")
        ttk.Checkbutton(gf, text="affiché sur le stream (Total : x / objectif)", variable=goal_on).pack(side="left", padx=(8, 0))

        def ok():
            self.cfg["token"] = token.get().strip()
            self.cfg["channel"] = channel.get()
            save_config(self.cfg)
            if ctl is not None:
                body = {"action": "goal", "enabled": bool(goal_on.get())}
                g = goal.get().strip().replace(",", ".")
                if g:
                    try:
                        body["goal"] = float(g)
                    except ValueError:
                        messagebox.showerror("TurboIRL", "Objectif invalide")
                        return
                try:
                    ctl.post_orders(body)
                except Exception as e:
                    messagebox.showerror("TurboIRL", f"Objectif non enregistré (VPS) : {e}")
                    return
            win.destroy()

        ttk.Button(f, text="Enregistrer", command=ok).grid(row=4, column=1, sticky="e")

    def clear_orders(self):
        ctl = self.receiver.control if self.receiver is not None else None
        if ctl is None:
            return
        if not messagebox.askyesno("TurboIRL", "Remettre le compteur de commandes et le total à zéro ?"):
            return
        try:
            ctl.post_orders({"action": "clear"})
            receiver.log("commandes effacées depuis le logiciel PC")
        except Exception as e:
            messagebox.showerror("TurboIRL", f"Effacement impossible (VPS) : {e}")

    def toggle_autostart(self):
        self.cfg["autostart"] = bool(self.autostart.get())
        save_config(self.cfg)
        try:
            set_autostart(self.cfg["autostart"])
        except Exception as e:
            messagebox.showerror("TurboIRL", f"Raccourci de démarrage impossible : {e}")

    def quit(self, ask=True):
        if ask and self.receiver is not None and self.receiver.decoder_connected:
            if not messagebox.askyesno("TurboIRL", "Le téléphone envoie un flux en ce moment. Fermer quand même ?\n(OBS et son stream ne sont pas touchés, mais l'image se figera.)"):
                return
        if self.receiver is not None:
            try:
                self.receiver.shutdown()
            except Exception:
                pass
        if self.journal is not None:
            receiver.log("logiciel PC fermé")
            self.journal.flush(timeout=3)
        self.root.destroy()
        os._exit(0)


def main():
    root = tk.Tk()
    try:
        root.iconbitmap(default=os.path.join(RES_DIR, "turboirl.ico"))
    except Exception:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
