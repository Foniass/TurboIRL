package fr.turboirl.app

import android.media.MediaExtractor
import android.media.MediaFormat
import fr.turboirl.core.rtmp.Logger
import fr.turboirl.core.rtmp.RtmpListener
import java.io.File
import java.nio.ByteBuffer

/**
 * Test source for the bench: plays an MP4 (H.264 + AAC, no B-frames) in a loop and feeds it to the relay exactly
 * as the RTMP server feeds a GoPro stream (FLV tag bodies with timestamps), so the whole pipeline runs for real
 * without a camera. The bench video carries its frame number in a bar code and a continuous tone, which the PC
 * side reads to count freezes, missing frames and sound holes.
 */
class FakeCamera(private val file: File, private val listener: RtmpListener, private val logger: Logger) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::loop, "fake-camera").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(3000)
        thread = null
    }

    private fun loop() {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            var vTrack = -1
            var aTrack = -1
            var vFormat: MediaFormat? = null
            var aFormat: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime == "video/avc" && vTrack < 0) { vTrack = i; vFormat = f }
                if (mime == "audio/mp4a-latm" && aTrack < 0) { aTrack = i; aFormat = f }
            }
            val vf = vFormat ?: throw IllegalStateException("pas de piste H.264 dans ${file.name}")
            ex.selectTrack(vTrack)
            if (aTrack >= 0) ex.selectTrack(aTrack)
            val width = vf.getInteger(MediaFormat.KEY_WIDTH)
            val height = vf.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = vf.getLong(MediaFormat.KEY_DURATION)
            val sps = nal(vf.getByteBuffer("csd-0")!!)
            val pps = nal(vf.getByteBuffer("csd-1")!!)
            val asc = aFormat?.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }

            listener.onPublishStart("source de test", "live", "bench")
            listener.onMetadata(mapOf("width" to width.toDouble(), "height" to height.toDouble(), "framerate" to 30.0, "videodatarate" to 3000.0))
            // FLV AVC sequence header: [0x17, 0, cts(3)] + AVCDecoderConfigurationRecord (4-byte NAL lengths)
            val avcc = ByteArray(11 + sps.size + pps.size)
            avcc[0] = 1; avcc[1] = sps[1]; avcc[2] = sps[2]; avcc[3] = sps[3]; avcc[4] = 0xFF.toByte(); avcc[5] = 0xE1.toByte()
            avcc[6] = (sps.size shr 8).toByte(); avcc[7] = sps.size.toByte()
            System.arraycopy(sps, 0, avcc, 8, sps.size)
            var p = 8 + sps.size
            avcc[p++] = 1; avcc[p++] = (pps.size shr 8).toByte(); avcc[p++] = pps.size.toByte()
            System.arraycopy(pps, 0, avcc, p, pps.size)
            listener.onVideo(0, byteArrayOf(0x17, 0, 0, 0, 0) + avcc)
            if (asc != null) listener.onAudio(0, byteArrayOf(0xAF.toByte(), 0) + asc)
            logger.log("Source de test : ${file.name} (${width}x$height, ${durationUs / 1_000_000} s en boucle)")

            val buf = ByteBuffer.allocate(1 shl 20)
            val startNs = System.nanoTime()
            var loopOffsetUs = 0L
            var loops = 0
            var nVideo = 0L
            var nAudio = 0L
            var lastReport = System.nanoTime()
            while (running) {
                if (System.nanoTime() - lastReport > 30_000_000_000L) {
                    lastReport = System.nanoTime()
                    logger.log("Source de test : $nVideo images et $nAudio trames son envoyées, ${(System.nanoTime() - startNs) / 1_000_000_000} s écoulées")
                }
                val n = ex.readSampleData(buf, 0)
                if (n < 0) {
                    loops++
                    loopOffsetUs += durationUs
                    ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    continue
                }
                val track = ex.sampleTrackIndex
                val tUs = ex.sampleTime + loopOffsetUs
                val sync = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                // real-time pacing on the sample timestamps
                val wait = startNs + tUs * 1000 - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                val data = ByteArray(n).also { buf.get(it, 0, n); buf.clear() }
                val tMs = tUs / 1000
                if (track == vTrack) {
                    nVideo++
                    if (nVideo == 1L) logger.log("Source de test : première image ${if (sync) "clé" else "NON clé"} de $n octets, NAL ${data.take(5).joinToString(" ") { "%02x".format(it) }}")
                    listener.onVideo(tMs, byteArrayOf(if (sync) 0x17 else 0x27, 1, 0, 0, 0) + data)
                } else {
                    nAudio++
                    listener.onAudio(tMs, byteArrayOf(0xAF.toByte(), 1) + data)
                }
                ex.advance()
            }
            listener.onPublishEnd("source de test arrêtée après $loops boucle(s)")
        } catch (_: InterruptedException) {
            listener.onPublishEnd("source de test arrêtée")
        } catch (e: Exception) {
            logger.log("Source de test : ${e.message ?: e.javaClass.simpleName}")
            listener.onPublishEnd("source de test en erreur")
        } finally {
            ex.release()
        }
    }

    /** csd buffers carry an Annex-B start code: return the bare NAL unit. */
    private fun nal(b: ByteBuffer): ByteArray {
        val all = ByteArray(b.remaining()).also { b.duplicate().get(it) }
        var i = 0
        while (i + 2 < all.size && !(all[i].toInt() == 0 && all[i + 1].toInt() == 0 && all[i + 2].toInt() == 1)) i++
        return if (i + 2 < all.size) all.copyOfRange(i + 3, all.size) else all
    }
}
