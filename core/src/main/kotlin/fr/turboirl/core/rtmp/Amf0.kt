package fr.turboirl.core.rtmp

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Minimal AMF0 codec: just what an RTMP publish handshake needs.
 * Decoded values are Double, Boolean, String, Map<String, Any?>, List<Any?> or null.
 */
object Amf0 {

    class Reader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

        fun hasMore(): Boolean = pos < end

        fun read(): Any? {
            return when (val type = u8()) {
                0x00 -> java.lang.Double.longBitsToDouble(u64())
                0x01 -> u8() != 0
                0x02 -> string(u16())
                0x03 -> readObject()
                0x05, 0x06 -> null
                0x08 -> {
                    pos += 4 // approximate count, the end marker is what matters
                    readObject()
                }
                0x0A -> {
                    val count = u32().toInt()
                    if (count < 0 || count > end - pos) throw IOException("AMF0: bad strict array size $count")
                    List(count) { read() }
                }
                0x0B -> {
                    val date = java.lang.Double.longBitsToDouble(u64())
                    pos += 2
                    date
                }
                0x0C -> string(u32().toInt())
                else -> throw IOException("AMF0: unsupported type 0x${type.toString(16)}")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                val key = string(u16())
                if (key.isEmpty() && pos < end && buf[pos].toInt() == 0x09) {
                    pos++
                    return map
                }
                map[key] = read()
            }
        }

        private fun need(n: Int) {
            if (n < 0 || pos + n > end) throw IOException("AMF0: truncated")
        }

        private fun u8(): Int {
            need(1)
            return buf[pos++].toInt() and 0xFF
        }

        private fun u16(): Int = (u8() shl 8) or u8()

        private fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        private fun u64(): Long = (u32() shl 32) or u32()

        private fun string(len: Int): String {
            need(len)
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }
    }

    class Writer {
        private val out = ByteArrayOutputStream()

        fun string(s: String): Writer {
            out.write(0x02)
            rawString(s)
            return this
        }

        fun number(d: Double): Writer {
            out.write(0x00)
            val bits = java.lang.Double.doubleToLongBits(d)
            for (shift in 56 downTo 0 step 8) out.write((bits ushr shift).toInt() and 0xFF)
            return this
        }

        fun nullValue(): Writer {
            out.write(0x05)
            return this
        }

        /** Values may be String, Number or Boolean. */
        fun obj(props: Map<String, Any>): Writer {
            out.write(0x03)
            for ((k, v) in props) {
                rawString(k)
                when (v) {
                    is String -> string(v)
                    is Number -> number(v.toDouble())
                    is Boolean -> {
                        out.write(0x01)
                        out.write(if (v) 1 else 0)
                    }
                    else -> throw IllegalArgumentException("AMF0: unsupported property type for $k")
                }
            }
            out.write(0)
            out.write(0)
            out.write(0x09)
            return this
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun rawString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            out.write(bytes.size shr 8)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
    }
}
