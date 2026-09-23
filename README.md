# TurboIRL

Relais de streaming IRL pour Android : la GoPro envoie son flux RTMP au téléphone (via le
partage de connexion), l'appli le réemballe en MPEG-TS **sans réencodage** et le pousse en SRT
vers un PC avec OBS.

```
GoPro ──RTMP (Wi-Fi hotspot)──▶ téléphone (TurboIRL) ──SRT (4G/5G)──▶ PC / OBS ──▶ Twitch/Kick
```

## Structure

- `core/` — Kotlin pur (aucune dépendance Android) : serveur RTMP d'ingest, démux FLV, mux MPEG-TS.
  Testable sur PC.
- `app/` — appli Android : foreground service, envoi SRT (libsrt via srtdroid), écran d'état.

## Compiler

L'outillage est dans `C:\Users\Fonias\Android` (JDK 21, SDK Android). Pas besoin d'Android Studio.

```bash
./gradlew :app:assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`. Installation par USB (débogage USB activé) :

```bash
C:/Users/Fonias/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tester le cœur sans téléphone

```bash
./gradlew :core:installDist
core/build/install/core/bin/core --port 1935 --file out.ts
```

Puis, dans un autre terminal, simuler la GoPro :

```bash
ffmpeg -re -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=sample_rate=48000 -t 10 -c:v libx264 -g 60 -b:v 3000k -pix_fmt yuv420p -c:a aac -f flv rtmp://127.0.0.1:1935/live/gopro
```

`ffprobe out.ts` doit montrer un flux H.264 + AAC lisible.

## Mise en place

### 1. PC récepteur (pendant le dev : le PC de Fonias)

1. Livebox 7 : *Paramètres avancés → Réseau → DHCP* : bail statique pour le PC, puis
   *NAT/PAT* : UDP **9000** externe → 9000 interne vers le PC. Vérifier dans *Internet* que
   l'IPv4 n'est pas « partagée » (sinon n'utiliser qu'un port de la plage autorisée).
2. Lancer le récepteur (crée la règle pare-feu la première fois) :

   ```bash
   powershell -ExecutionPolicy Bypass -File tools/recv.ps1
   ```

   Il écoute le SRT, enregistre chaque session dans `dumps/` et renvoie le flux à OBS en trois étages
   (`tools/receiver.py`, nécessite python 3) : ffmpeg décode et imprime le PTS de chaque image et de chaque
   bloc audio, le répéteur joue le son sur l'horloge murale (réserve 700 ms, silence si le son manque) et y
   asservit l'image (dernière image dont le PTS est atteint par le son joué, répétée si l'image manque), ffmpeg
   encode vers OBS. L'encodeur ne redémarre jamais : OBS ne voit jamais de trou, même quand le téléphone se
   reconnecte, et son et image ne peuvent pas se désaligner. `tools/obs.py` pilote l'enregistrement OBS
   (obs-websocket, activé dans Outils → Paramètres du serveur WebSocket, sans authentification).
   Pour rejouer un dump vers OBS exactement comme en direct (reproduire un incident, valider une
   correction du récepteur sans sortie terrain ; nécessite python 3) :

   ```bash
   powershell -ExecutionPolicy Bypass -File tools/replay.ps1 -Dump dumps/dump-20260923-181204.ts -StartSec 440 -DurationSec 60
   ```
3. OBS : Source → **Source multimédia**, décocher « Fichier local », entrée `udp://127.0.0.1:9001`,
   format d'entrée `mpegts`. (Sans le script : `srt://0.0.0.0:9000?mode=listener&latency=2000000`
   directement dans OBS, mais plus de dump.)
4. Adresse à saisir dans l'appli : l'IP publique de la box (https://ifconfig.me) ou un nom
   DynDNS (*Livebox → Réseau → DynDNS*, compte No-IP gratuit).

### 2. Téléphone (Redmi / HyperOS)

1. Installer l'APK, autoriser les notifications.
2. Dans l'appli : bouton « Autoriser l'exécution en arrière-plan ».
3. Réglages HyperOS → Applications → TurboIRL : **Démarrage automatique** activé, Économie
   de batterie → **Aucune restriction**. Dans les applis récentes, appui long sur TurboIRL → cadenas.
4. Activer le partage de connexion, de préférence en 5 GHz.
5. Saisir l'adresse du PC (nom DynDNS), port 9000, latence 2000 ms → **Démarrer**.
   L'appli affiche l'URL RTMP à donner à la GoPro.

### 3. GoPro Hero 12

**Pilotage automatique (recommandé)** : cocher « L'appli connecte la GoPro… », saisir le nom et
le mot de passe du hotspot du téléphone, la résolution (720) et le débit max (2500), Démarrer.
La première fois : mettre la caméra en mode appairage (*Préférences → Connexions → Connecter un
appareil → Application GoPro Quik*) et accepter la demande d'appairage Bluetooth sur le téléphone.
Fermer Quik pendant ce temps (une seule appli peut tenir la caméra en Bluetooth). Ensuite l'appli
se reconnecte seule, enregistre le hotspot dans la caméra, configure le live (Open GoPro) vers
l'adresse courante du hotspot, le démarre et le relance s'il tombe.

**À la main (secours)** : dans Quik, Live → RTMP → Wi-Fi du hotspot → coller l'URL affichée par
l'appli (`rtmp://<ip du hotspot>:1935/live/gopro`). Android peut changer l'IP du hotspot d'une
session à l'autre : vérifier l'URL si la GoPro ne se connecte plus.

## Protocole de test

Chaque étape isole une inconnue. Après chaque étape, bouton **Partager le journal** → Discord.

1. **Appli seule** : hotspot allumé, Démarrer → « SRT ✓ » et `recv.ps1` affiche la connexion.
   Valide box, NAT Orange et lib SRT.
2. **+ GoPro** sur le hotspot → image dans OBS. Valide l'ingest et le mux ; analyser le dump.
3. **Téléphone en poche, Uber Eats au premier plan, 20 min.** Valide HyperOS : le journal
   affiche au redémarrage la raison de toute fin de process.
4. **Tour du quartier en 5G.** Valide la tenue du lien (RTT, retransmis, perdus dans l'écran État).

## Diffusion de l'APK

```bash
./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk dist/TurboIRL-X.Y.apk
gh release create vX.Y dist/TurboIRL-X.Y.apk --title "TurboIRL X.Y" --notes "..."
```

## Modes vidéo (au choix dans l'écran)

1. **Direct** (défaut) : la vidéo de la GoPro passe telle quelle. Si la 5G sature, la vidéo est
   suspendue par paliers mais le son continue.
2. **Débit modulable** (0.8, expérimental) : l'appli freine sa lecture du flux caméra quand le
   tampon SRT gonfle ; la GoPro baisse alors son propre débit (800 kb/s mini). Sans réencodage.
3. **Réencodage sur le téléphone** (défaut depuis 0.9) : décodage matériel → redimensionnement OpenGL →
   encodage matériel à un débit piloté par SRT (400 kb/s → « débit max en sortie »), résolution
   480p/720p/1080p suivant le débit (« résolution max en sortie »), 15 i/s sous 700 kb/s. Le son
   de la GoPro passe tel quel. Régler la GoPro en **1080p / 5000 kb/s** pour une meilleure source.
   Le son est lui aussi réencodé (AAC 64 kb/s par défaut) pour tenir dans les zones faibles.
   Réglages par défaut = ceux du test extérieur : réencodage, 3000 kb/s max, 720p max, son 64 kb/s,
   GoPro pilotée en 720p / 4000 kb/s.

## Réception : règle importante

OBS (source multimédia) perd sa synchro audio de façon durable dès que la vidéo s'interrompt
quelques secondes, même si l'audio est continu (reproduit sur PC : `core --congest 40:5` hache le
son dans OBS, `--trickle` non). L'appli ne laisse donc jamais de trou vidéo : mode dégradé
(150 kb/s, 5 i/s) avec le réencodeur, image clé par seconde en direct. Si OBS hache quand même,
désactiver/réactiver la source le remet d'aplomb. En plus, `tools/recv.ps1` réencode le flux vers OBS en
cadence constante (dernière image répétée, silence inséré) : même une coupure totale du réseau ou un
redémarrage de la caméra ne crée pas de trou côté OBS (vérifié : test A haché, test C propre).

Trouvé sur le test du 23/09 (18h20) et reproduit en relecture du dump : pendant les 8 s où le téléphone
a retenu la vidéo (mode critique), le son arrivait en continu au PC, mais OBS a produit 6 s de silence.
La source multimédia d'OBS retient le son tant qu'elle ne reçoit pas d'image plus récente, et ffmpeg ne
peut pas dupliquer une image *avant* d'avoir reçu la suivante (en plus, son muxeur retenait le son jusqu'à
10 s sans `-max_interleave_delta`, et le décodeur HEVC multi-thread ajoutait 3 s de retard à 5 i/s).
D'où le répéteur sur horloge murale : validé en relecture (`tools/replay.ps1`), OBS enregistre le son sans
coupure pendant l'image figée, là où l'ancien récepteur donnait 5,9 s de silence. Deuxième piège : le
téléphone (≤ 0.99) met 16 trames AAC dans chaque PES audio, le décodeur livre donc son et image par rafales de
340 ms ; sans les PTS (filtres metadata avec `direct=1`, conversion de cadence par le filtre `fps` avant
l'impression) l'image répétée tombait à 5 i/s ou se décalait du son.

## Limites connues

- H.264 + AAC uniquement. Une seule caméra à la fois.
- Réencodage et pilotage caméra écrits d'après la doc, à valider sur le terrain ; le journal
  (« Partager le journal ») contient tout ce qu'il faut pour diagnostiquer.
