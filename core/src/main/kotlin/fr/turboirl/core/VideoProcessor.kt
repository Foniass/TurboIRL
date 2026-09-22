package fr.turboirl.core

/**
 * Optional stage between the camera's H.264 and the muxer (a transcoder). Frames are handed
 * over in Annex B on the relay's output timeline; whatever comes back through [EncodedVideoSink]
 * is muxed in place of the original video.
 */
interface VideoProcessor {
    /** False when the processor gave up: the relay then muxes the camera's video untouched. */
    val active: Boolean

    /** Called whenever the incoming stream's parameter sets change. NAL units without start codes. */
    fun configure(sps: ByteArray, pps: ByteArray, width: Int, height: Int)

    /** [annexB] belongs to the processor after this call. */
    fun frame(annexB: ByteArray, ptsMs: Long, dtsMs: Long, keyframe: Boolean)

    /** Video was withheld for a while: the next output frame must be decodable on its own. */
    fun requestKeyframe()

    fun release()
}

fun interface EncodedVideoSink {
    /** One encoded access unit in Annex B (AUD + SPS/PPS on keyframes + slices), no B-frames. */
    fun encoded(annexB: ByteArray, len: Int, ptsMs: Long, keyframe: Boolean)
}
