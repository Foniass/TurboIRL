package fr.turboirl.core.ts

/**
 * Receives MPEG-TS data, always a multiple of 188 bytes and at most [TsMuxer.MAX_BATCH]
 * (the SRT payload size). Must not block for long: it is called from the
 * RTMP reader thread.
 */
fun interface TsSink {
    fun write(buf: ByteArray, off: Int, len: Int)
}

/** Single program MPEG-TS muxer: one H.264 video stream, optionally one AAC (ADTS) audio stream. */
class TsMuxer(private val sink: TsSink) {

    private val batch = ByteArray(MAX_BATCH)
    private var batchLen = 0
    private var batchStartedNs = 0L

    private var hasAudio = false
    private var pmtVersion = 0
    private var patCc = 0
    private var pmtCc = 0
    private var videoCc = 0
    private var audioCc = 0
    private var lastPsiNs = 0L

    var bytesWritten = 0L
        private set

    /** MPEG-TS stream type of the video: 0x1B = H.264, 0x24 = H.265. Changing it re-announces the PMT. */
    var videoStreamType = STREAM_TYPE_H264
        set(value) {
            if (value != field) {
                field = value
                pmtVersion = (pmtVersion + 1) and 0x1F
                lastPsiNs = 0
            }
        }

    fun setHasAudio(value: Boolean) {
        if (value != hasAudio) {
            hasAudio = value
            pmtVersion = (pmtVersion + 1) and 0x1F
            lastPsiNs = 0
        }
    }

    /** [data] is one Annex B access unit. Timestamps are in 90 kHz units. */
    fun writeVideo(data: ByteArray, len: Int, pts: Long, dts: Long, pcr: Long, keyframe: Boolean) {
        val now = System.nanoTime()
        if (keyframe || lastPsiNs == 0L || now - lastPsiNs > PSI_INTERVAL_NS) {
            writePsi()
            lastPsiNs = now
        }
        writePes(VIDEO_PID, 0xE0, data, len, pts, dts, pcr, keyframe)
        flush()
    }

    /** [data] is one or more ADTS frames. */
    fun writeAudio(data: ByteArray, len: Int, pts: Long) {
        if (lastPsiNs == 0L) return // nothing decodable before the first PAT/PMT + keyframe
        writePes(AUDIO_PID, 0xC0, data, len, pts, pts, NO_PCR, false)
        // Normally flushed along with the next video frame; don't let audio rot if video stalls.
        if (batchLen > 0 && System.nanoTime() - batchStartedNs > AUDIO_FLUSH_NS) flush()
    }

    /**
     * Keeps the clock alive while video is being withheld: a payload-less packet on the PCR PID,
     * plus PAT/PMT now and then so that a receiver joining mid-way can still lock on.
     */
    fun writePcrOnly(pcr: Long) {
        if (lastPsiNs == 0L) return
        val now = System.nanoTime()
        if (now - lastPsiNs > PSI_INTERVAL_NS) {
            writePsi()
            lastPsiNs = now
        }
        val off = nextPacket()
        batch[off] = 0x47
        batch[off + 1] = (VIDEO_PID shr 8).toByte()
        batch[off + 2] = VIDEO_PID.toByte()
        batch[off + 3] = (0x20 or videoCc).toByte() // adaptation field only: CC is not incremented
        batch[off + 4] = 183.toByte()
        batch[off + 5] = 0x10
        val base = pcr and TS_MASK
        batch[off + 6] = (base shr 25).toByte()
        batch[off + 7] = (base shr 17).toByte()
        batch[off + 8] = (base shr 9).toByte()
        batch[off + 9] = (base shr 1).toByte()
        batch[off + 10] = (((base and 1L) shl 7) or 0x7E).toByte()
        batch[off + 11] = 0
        for (p in off + 12 until off + TS_PACKET) batch[p] = 0xFF.toByte()
    }

    fun flush() {
        if (batchLen > 0) {
            sink.write(batch, 0, batchLen)
            bytesWritten += batchLen
            batchLen = 0
        }
    }

    private fun nextPacket(): Int {
        if (batchLen == MAX_BATCH) flush()
        if (batchLen == 0) batchStartedNs = System.nanoTime()
        val off = batchLen
        batchLen += TS_PACKET
        return off
    }

    private fun writePes(pid: Int, streamId: Int, data: ByteArray, len: Int, pts: Long, dts: Long, pcr: Long, keyframe: Boolean) {
        val withDts = dts != pts
        val headerDataLen = if (withDts) 10 else 5
        val header = ByteArray(9 + headerDataLen)
        header[2] = 1
        header[3] = streamId.toByte()
        val pesLen = 3 + headerDataLen + len
        if (pesLen <= 0xFFFF && pid != VIDEO_PID) {
            header[4] = (pesLen shr 8).toByte()
            header[5] = pesLen.toByte()
        } // else 0 = unbounded, allowed for video
        header[6] = 0x84.toByte() // '10', data_alignment_indicator
        header[7] = (if (withDts) 0xC0 else 0x80).toByte()
        header[8] = headerDataLen.toByte()
        putTimestamp(header, 9, if (withDts) 3 else 2, pts)
        if (withDts) putTimestamp(header, 14, 1, dts)

        val total = header.size + len
        var pos = 0
        var first = true
        while (pos < total) {
            val off = nextPacket()
            val withPcr = first && pcr != NO_PCR
            var afLen = if (withPcr) 8 else 0 // adaptation field size, length byte included
            val remaining = total - pos
            if (remaining < 184 - afLen) afLen = 184 - remaining
            val cc = if (pid == VIDEO_PID) {
                videoCc = (videoCc + 1) and 0xF
                videoCc
            } else {
                audioCc = (audioCc + 1) and 0xF
                audioCc
            }
            batch[off] = 0x47
            batch[off + 1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            batch[off + 2] = pid.toByte()
            batch[off + 3] = ((if (afLen > 0) 0x30 else 0x10) or cc).toByte()
            var p = off + 4
            if (afLen > 0) {
                batch[p++] = (afLen - 1).toByte()
                if (afLen > 1) {
                    batch[p++] = ((if (withPcr) 0x10 else 0) or (if (first && keyframe) 0x40 else 0)).toByte()
                    if (withPcr) {
                        val base = pcr and TS_MASK
                        batch[p++] = (base shr 25).toByte()
                        batch[p++] = (base shr 17).toByte()
                        batch[p++] = (base shr 9).toByte()
                        batch[p++] = (base shr 1).toByte()
                        batch[p++] = (((base and 1L) shl 7) or 0x7E).toByte()
                        batch[p++] = 0
                    }
                    val afEnd = off + 4 + afLen
                    while (p < afEnd) batch[p++] = 0xFF.toByte()
                }
            }
            // Payload: PES header first, then the elementary stream data.
            val end = off + TS_PACKET
            while (p < end) {
                val n: Int
                if (pos < header.size) {
                    n = minOf(end - p, header.size - pos)
                    System.arraycopy(header, pos, batch, p, n)
                } else {
                    n = end - p
                    System.arraycopy(data, pos - header.size, batch, p, n)
                }
                p += n
                pos += n
            }
            first = false
        }
    }

    private fun writePsi() {
        patCc = (patCc + 1) and 0xF
        writeSection(
            PAT_PID, patCc,
            byteArrayOf(
                0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
                0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte(),
            )
        )

        val streams = if (hasAudio) 2 else 1
        val sectionLen = 9 + 5 * streams + 4
        val pmt = ByteArray(3 + sectionLen - 4)
        pmt[0] = 0x02
        pmt[1] = (0xB0 or (sectionLen shr 8)).toByte()
        pmt[2] = sectionLen.toByte()
        pmt[3] = 0x00
        pmt[4] = 0x01 // program number
        pmt[5] = (0xC1 or (pmtVersion shl 1)).toByte()
        pmt[8] = (0xE0 or (VIDEO_PID shr 8)).toByte() // PCR PID
        pmt[9] = VIDEO_PID.toByte()
        pmt[10] = 0xF0.toByte()
        var p = 12
        p = putStream(pmt, p, videoStreamType, VIDEO_PID)
        if (hasAudio) putStream(pmt, p, 0x0F, AUDIO_PID)
        pmtCc = (pmtCc + 1) and 0xF
        writeSection(PMT_PID, pmtCc, pmt)
    }

    private fun putStream(b: ByteArray, off: Int, streamType: Int, pid: Int): Int {
        b[off] = streamType.toByte()
        b[off + 1] = (0xE0 or (pid shr 8)).toByte()
        b[off + 2] = pid.toByte()
        b[off + 3] = 0xF0.toByte()
        b[off + 4] = 0
        return off + 5
    }

    /** [section] is the table without its CRC. */
    private fun writeSection(pid: Int, cc: Int, section: ByteArray) {
        val off = nextPacket()
        batch[off] = 0x47
        batch[off + 1] = (0x40 or (pid shr 8)).toByte()
        batch[off + 2] = pid.toByte()
        batch[off + 3] = (0x10 or cc).toByte()
        batch[off + 4] = 0 // pointer field
        var p = off + 5
        System.arraycopy(section, 0, batch, p, section.size)
        p += section.size
        val crc = crc32(section)
        batch[p++] = (crc shr 24).toByte()
        batch[p++] = (crc shr 16).toByte()
        batch[p++] = (crc shr 8).toByte()
        batch[p++] = crc.toByte()
        val end = off + TS_PACKET
        while (p < end) batch[p++] = 0xFF.toByte()
    }

    private fun putTimestamp(b: ByteArray, off: Int, prefix: Int, value: Long) {
        val v = value and TS_MASK
        b[off] = ((prefix shl 4) or (((v shr 30).toInt() and 0x7) shl 1) or 1).toByte()
        b[off + 1] = (v shr 22).toByte()
        b[off + 2] = ((((v shr 15).toInt() and 0x7F) shl 1) or 1).toByte()
        b[off + 3] = (v shr 7).toByte()
        b[off + 4] = (((v.toInt() and 0x7F) shl 1) or 1).toByte()
    }

    companion object {
        const val TS_PACKET = 188
        // 6 packets = 1128 B payload: stays under the 1280 B IPv6 minimum MTU even through the
    // 464XLAT + CGNAT of mobile carriers, where 7 packets (1316 B) may be silently dropped.
    const val MAX_BATCH = 6 * TS_PACKET
        const val NO_PCR = -1L
        const val STREAM_TYPE_H264 = 0x1B
        const val STREAM_TYPE_H265 = 0x24

        private const val PAT_PID = 0
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x100
        private const val AUDIO_PID = 0x101
        private const val TS_MASK = 0x1FFFFFFFFL
        private const val PSI_INTERVAL_NS = 400_000_000L
        private const val AUDIO_FLUSH_NS = 50_000_000L

        private val CRC_TABLE = IntArray(256) { i ->
            var c = i shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            c
        }

        internal fun crc32(data: ByteArray): Int {
            var crc = -1
            for (b in data) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
            return crc
        }
    }
}
