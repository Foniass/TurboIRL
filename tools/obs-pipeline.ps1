# Pipeline commun à recv.ps1 (flux live) et replay.ps1 (relecture d'un dump). Un seul endroit à modifier
# pour que le test sur dump reproduise exactement le direct.
#
#   source (SRT ou dump rejoué) ─► ffmpeg décodeur ─► repeater.py ─► ffmpeg encodeur ─► udp://127.0.0.1:9001 (OBS)
#                                       └─► dump brut (-c copy)      (horloge murale)     (ne redémarre jamais)
#
# Pourquoi trois étages : la source multimédia d'OBS retient le son tant qu'elle ne reçoit pas d'image plus
# récente. Quand le téléphone retient la vidéo (mode critique, zone morte), ffmpeg seul ne peut pas dupliquer
# la dernière image avant d'avoir reçu la suivante : OBS restait sans image ET sans son (6 s de silence sur le
# test du 23/09 18h20 alors que le son arrivait en continu). Le répéteur ressort la dernière image à 30 i/s et
# le son cadencés sur l'horloge murale, quoi qu'il arrive en entrée ; l'encodeur voit donc toujours un flux
# régulier et OBS ne voit jamais de trou, même pendant une reconnexion du téléphone.

$RepeaterPorts = @{ InVideo = 9021; InAudio = 9022; OutVideo = 9031; OutAudio = 9032 }
$RepeaterSize = "1280x720"
$RepeaterFps = 30

# Options d'entrée du décodeur : timestamps régénérés si absents, tolérance aux sauts d'horloge du téléphone.
# -threads 1 : le décodeur HEVC multi-thread garde ~16 images en attente, soit 3 s de retard à 5 i/s (mode dégradé).
$InputArgs = @("-fflags", "+genpts+nobuffer", "-flags", "low_delay", "-analyzeduration", "2000000", "-probesize", "1000000", "-dts_delta_threshold", "1000", "-threads", "1")

function Start-Repeater {
    Start-Process python -NoNewWindow -PassThru -ArgumentList @(
        "`"$(Join-Path $PSScriptRoot 'repeater.py')`"",  # guillemets : le chemin du repo contient un espace
        "--in-video", $RepeaterPorts.InVideo, "--in-audio", $RepeaterPorts.InAudio,
        "--out-video", $RepeaterPorts.OutVideo, "--out-audio", $RepeaterPorts.OutAudio,
        "--size", $RepeaterSize, "--fps", $RepeaterFps
    )
}

function Start-Encoder([int]$ObsPort) {
    # Lit les images brutes et le PCM du répéteur (déjà à cadence fixe), encode vers OBS.
    # -crf 18 plafonné à 10 Mb/s : en débit imposé, x264 dépensait 10 Mb/s même sur les images répétées d'un plan
    # figé ; en qualité constante une image identique à la précédente ne coûte presque rien.
    Start-Process ffmpeg -NoNewWindow -PassThru -ArgumentList @(
        "-hide_banner", "-loglevel", "warning", "-stats", "-stats_period", "5",
        "-thread_queue_size", "1024", "-probesize", "32", "-analyzeduration", "0",
        "-f", "rawvideo", "-pix_fmt", "yuv420p", "-video_size", $RepeaterSize, "-framerate", $RepeaterFps,
        "-i", "tcp://127.0.0.1:$($RepeaterPorts.OutVideo)",
        "-thread_queue_size", "1024", "-probesize", "32", "-analyzeduration", "0",
        "-f", "s16le", "-ar", "48000", "-ac", "2",
        "-i", "tcp://127.0.0.1:$($RepeaterPorts.OutAudio)",
        "-map", "0:v", "-map", "1:a",
        "-c:v", "libx264", "-preset", "faster", "-tune", "zerolatency", "-g", "60",
        "-crf", "18", "-maxrate", "10M", "-bufsize", "10M", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "160k",
        "-max_interleave_delta", "1000000",
        "-f", "mpegts", "udp://127.0.0.1:${ObsPort}?pkt_size=1316"
    )
}

function DecoderOutputArgs {
    # Sorties du décodeur vers le répéteur : images brutes 720p (l'échelle absorbe un passage du téléphone en 1080p)
    # et PCM 48 kHz stéréo. -fps_mode passthrough : les images partent telles qu'elles arrivent, la cadence est
    # l'affaire du répéteur. Le dump brut (-c copy) est ajouté par recv.ps1.
    $w, $h = $RepeaterSize.Split("x")
    @(
        "-map", "0:v:0", "-fps_mode", "passthrough", "-vf", "scale=${w}:${h},format=yuv420p",
        "-flush_packets", "1", "-f", "rawvideo", "tcp://127.0.0.1:$($RepeaterPorts.InVideo)",
        "-map", "0:a:0", "-af", "aresample=async=1000", "-flush_packets", "1", "-f", "s16le", "-ar", "48000", "-ac", "2",
        "tcp://127.0.0.1:$($RepeaterPorts.InAudio)"
    )
}
