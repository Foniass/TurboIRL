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

   Il écoute le SRT, enregistre chaque session dans `dumps/` et renvoie le flux à OBS.
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

## Limites connues (MVP)

- Pas de débit adaptatif : si l'upload passe sous le débit de la GoPro, l'appli suspend la
  vidéo (jusqu'à la prochaine image clé après retour à la normale) mais garde le son. Compteurs
  « vidéo en pause » et « perdus » dans l'écran État.
- H.264 + AAC uniquement. Une seule caméra à la fois.
- Le pilotage Bluetooth n'a pas encore été testé sur une vraie caméra (écrit d'après la spec
  Open GoPro et le SDK Python officiel) ; les tests unitaires couvrent le codec protobuf et le
  découpage des paquets BLE.
