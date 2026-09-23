# Pipeline ffmpeg commun à recv.ps1 (flux live) et replay.ps1 (relecture d'un dump) : options d'entrée et
# de sortie vers OBS. Un seul endroit à modifier pour que le test sur dump reproduise exactement le direct.

# Options d'entrée : timestamps régénérés si absents, tolérance aux sauts d'horloge du téléphone.
# -threads 1 : décodeur mono-thread. En multi-thread (par image), le décodeur HEVC garde ~16 images en
# attente ; à 5 i/s (mode dégradé) c'est 3 s de retard supplémentaire avant chaque image, et un trou vidéo
# de 8 s devenait 11 s côté OBS. 720p à 30 i/s se décode sans peine sur un seul cœur.
$InputArgs = @("-fflags", "+genpts", "-analyzeduration", "2000000", "-probesize", "1000000", "-dts_delta_threshold", "1000", "-threads", "1")

function ObsOutputArgs([int]$ObsPort) {
    # Réencodage en cadence constante : quand la vidéo du téléphone s'interrompt (zone morte, mode critique
    # « seul le son part »), ffmpeg répète la dernière image ; le son est comblé par du silence s'il manque.
    #
    # -max_interleave_delta 200000 : sans lui, le muxeur retient le son jusqu'à 10 s en attendant la vidéo ;
    # pendant un trou vidéo de 8 s OBS ne recevait donc plus rien (image figée ET silence), puis tout partait en
    # rafale. Constaté sur le test du 23/09 18h20 : dump avec son continu, OBS avec 7,7 s de silence. Avec 0,2 s,
    # le son continue en temps réel et les images dupliquées arrivent en rafale quand la vidéo reprend.
    #
    # -crf 18 plafonné à 10 Mb/s plutôt que -b:v 10M : en débit imposé, x264 dépensait 10 Mb/s même sur les images
    # dupliquées d'un plan figé, et la rafale après un trou de 8 s pesait 4 Mo d'un coup vers OBS (risque de perte
    # UDP). En qualité constante, une image identique à la précédente ne coûte presque rien.
    @(
        "-map", "0", "-fps_mode", "cfr", "-r", "30",
        "-c:v", "libx264", "-preset", "faster", "-tune", "zerolatency", "-g", "60",
        "-crf", "18", "-maxrate", "10M", "-bufsize", "10M", "-pix_fmt", "yuv420p",
        "-af", "aresample=async=1000", "-c:a", "aac", "-b:a", "160k",
        "-max_interleave_delta", "200000",
        "-f", "mpegts", "udp://127.0.0.1:${ObsPort}?pkt_size=1316"
    )
}
