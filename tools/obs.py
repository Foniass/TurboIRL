"""Pilote OBS depuis la ligne de commande via obs-websocket v5 (intégré à OBS 28+), sans dépendance.

Côté OBS : Outils → Paramètres du serveur WebSocket → activer le serveur (port 4455). Sans authentification
de préférence (le serveur n'écoute qu'en local) ; sinon mettre le mot de passe dans la variable
d'environnement OBS_WS_PASSWORD (le script ne le demande jamais et ne l'écrit nulle part).

Usage : python tools/obs.py status | start-record | stop-record | record-status
        stop-record affiche le chemin du fichier enregistré.
"""
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import uuid


class Ws:
    """Client WebSocket minimal (texte, trames masquées côté client)."""

    def __init__(self, host, port, timeout=5.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET / HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: obswebsocket.json\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("OBS a fermé la connexion pendant la poignée de main")
            head += chunk
        if b" 101 " not in head.split(b"\r\n", 1)[0]:
            raise ConnectionError("poignée de main WebSocket refusée : " + head.split(b"\r\n", 1)[0].decode(errors="replace"))
        self.buf = head.split(b"\r\n\r\n", 1)[1]

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("connexion fermée")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        while True:
            b0, b1 = self._read(2)
            opcode, ln = b0 & 0x0F, b1 & 0x7F
            if ln == 126:
                ln = struct.unpack("!H", self._read(2))[0]
            elif ln == 127:
                ln = struct.unpack("!Q", self._read(8))[0]
            mask = self._read(4) if b1 & 0x80 else None
            data = self._read(ln)
            if mask:
                data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
            if opcode == 1:
                return json.loads(data.decode())
            if opcode == 8:
                raise ConnectionError("OBS a fermé la connexion")
            # ping/pong/binaire : ignorés

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        ln = len(data)
        if ln < 126:
            head = bytes([0x81, 0x80 | ln])
        elif ln < 65536:
            head = bytes([0x81, 0x80 | 126]) + struct.pack("!H", ln)
        else:
            head = bytes([0x81, 0x80 | 127]) + struct.pack("!Q", ln)
        self.sock.sendall(head + mask + bytes(c ^ mask[i % 4] for i, c in enumerate(data)))


class Obs:
    def __init__(self, host="127.0.0.1", port=4455):
        self.ws = Ws(host, port)
        hello = self.ws.recv()
        if hello.get("op") != 0:
            raise RuntimeError(f"réponse inattendue d'OBS : {hello}")
        identify = {"rpcVersion": 1}
        auth = hello["d"].get("authentication")
        if auth:
            pwd = os.environ.get("OBS_WS_PASSWORD")
            if not pwd:
                raise RuntimeError(
                    "OBS demande une authentification : désactiver l'authentification dans Outils → Paramètres du "
                    "serveur WebSocket, ou définir la variable d'environnement OBS_WS_PASSWORD"
                )
            secret = base64.b64encode(hashlib.sha256((pwd + auth["salt"]).encode()).digest()).decode()
            identify["authentication"] = base64.b64encode(
                hashlib.sha256((secret + auth["challenge"]).encode()).digest()
            ).decode()
        self.ws.send({"op": 1, "d": identify})
        ident = self.ws.recv()
        if ident.get("op") != 2:
            raise RuntimeError(f"identification refusée : {ident}")

    def request(self, request_type, data=None):
        rid = str(uuid.uuid4())
        msg = {"op": 6, "d": {"requestType": request_type, "requestId": rid}}
        if data:
            msg["d"]["requestData"] = data
        self.ws.send(msg)
        while True:
            resp = self.ws.recv()
            if resp.get("op") == 7 and resp["d"].get("requestId") == rid:
                status = resp["d"]["requestStatus"]
                if not status.get("result"):
                    raise RuntimeError(f"{request_type} refusé par OBS : {status.get('comment', status)}")
                return resp["d"].get("responseData", {})


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    obs = Obs()
    if cmd == "status":
        v = obs.request("GetVersion")
        r = obs.request("GetRecordStatus")
        print(f"OBS {v['obsVersion']} (websocket {v['obsWebSocketVersion']}), enregistrement : "
              f"{'en cours, ' + r['outputTimecode'] if r['outputActive'] else 'arrêté'}")
    elif cmd == "record-status":
        r = obs.request("GetRecordStatus")
        print("actif" if r["outputActive"] else "arrêté", r.get("outputTimecode", ""))
    elif cmd == "start-record":
        obs.request("StartRecord")
        print("enregistrement démarré")
    elif cmd == "stop-record":
        r = obs.request("StopRecord")
        print(r.get("outputPath", ""))
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ConnectionError) as e:
        print(f"OBS injoignable sur 127.0.0.1:4455 ({e}) : le serveur WebSocket est-il activé dans Outils ?")
        sys.exit(1)
    except RuntimeError as e:
        print(e)
        sys.exit(1)
