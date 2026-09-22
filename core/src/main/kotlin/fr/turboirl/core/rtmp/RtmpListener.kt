package fr.turboirl.core.rtmp

fun interface Logger {
    fun log(msg: String)
}

/**
 * Callbacks for the single active publisher. All calls for one session come from that
 * session's thread, and the server never runs two sessions at once.
 * Audio/video payloads are raw FLV tag bodies.
 */
interface RtmpListener {
    fun onPublishStart(remote: String, app: String, streamKey: String)
    fun onMetadata(metadata: Map<String, Any?>)
    fun onVideo(timestampMs: Long, data: ByteArray)
    fun onAudio(timestampMs: Long, data: ByteArray)
    fun onPublishEnd(reason: String)
}
