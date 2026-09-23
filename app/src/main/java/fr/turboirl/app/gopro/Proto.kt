package fr.turboirl.app.gopro

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf (proto2 wire format) for the handful of Open GoPro messages we use:
 * varints (int32 / bool / enum) and length-delimited fields (string / bytes / embedded message).
 */
object Proto {

    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long): Writer {
            tag(field, 0)
            writeVarint(value)
            return this
        }

        fun bool(field: Int, value: Boolean) = varint(field, if (value) 1 else 0)

        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun bytes(field: Int, value: ByteArray): Writer {
            tag(field, 2)
            writeVarint(value.size.toLong())
            out.write(value)
            return this
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun tag(field: Int, wireType: Int) = writeVarint(((field shl 3) or wireType).toLong())

        private fun writeVarint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }
    }

    /** Decoded message: field number → values in order (Long for varints, ByteArray for length-delimited). */
    class Message(private val fields: Map<Int, List<Any>>) {
        fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long
        fun int(field: Int): Int? = long(field)?.toInt()
        fun bool(field: Int): Boolean? = long(field)?.let { it != 0L }
        fun string(field: Int): String? = (fields[field]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8)
        fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
        fun messages(field: Int): List<Message> = fields[field].orEmpty().filterIsInstance<ByteArray>().map { decode(it) }
        fun longs(field: Int): List<Long> = fields[field].orEmpty().filterIsInstance<Long>()
        override fun toString(): String = fields.entries.joinToString(", ") { (k, v) ->
            "$k=" + v.joinToString("|") { if (it is ByteArray) "[${it.size} o]" else it.toString() }
        }
    }

    fun decode(data: ByteArray, offset: Int = 0, end: Int = data.size): Message {
        val fields = LinkedHashMap<Int, MutableList<Any>>()
        var p = offset
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (p >= end) throw IllegalArgumentException("protobuf tronqué")
                val b = data[p++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalArgumentException("varint invalide")
            }
        }
        while (p < end) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val list = fields.getOrPut(field) { ArrayList() }
            when ((key and 7).toInt()) {
                0 -> list.add(varint())
                1 -> p += 8
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || p + len > end) throw IllegalArgumentException("protobuf tronqué")
                    list.add(data.copyOfRange(p, p + len))
                    p += len
                }
                5 -> p += 4
                else -> throw IllegalArgumentException("wire type protobuf inconnu")
            }
        }
        return Message(fields)
    }
}
