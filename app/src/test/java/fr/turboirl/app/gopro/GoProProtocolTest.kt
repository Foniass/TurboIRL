package fr.turboirl.app.gopro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoProProtocolTest {

    @Test
    fun protobufRoundTrip() {
        val bytes = Proto.Writer()
            .string(1, "rtmp://10.29.231.80:1935/live/gopro")
            .bool(2, false)
            .varint(3, 7)
            .varint(7, 800)
            .varint(8, 2500)
            .varint(9, 2500)
            .toByteArray()
        // field 1, wire type 2, length 35, then the url
        assertEquals(0x0A, bytes[0].toInt())
        assertEquals(35, bytes[1].toInt())
        val m = Proto.decode(bytes)
        assertEquals("rtmp://10.29.231.80:1935/live/gopro", m.string(1))
        assertEquals(false, m.bool(2))
        assertEquals(7, m.int(3))
        assertEquals(800, m.int(7))
        assertEquals(2500, m.int(8)) // two-byte varint
        assertNull(m.int(4))
    }

    @Test
    fun protobufRepeatedAndEmbedded() {
        val entry = Proto.Writer().string(1, "Redmi").varint(2, 3).varint(4, 2412).varint(5, 0x0A).toByteArray()
        val resp = Proto.Writer().varint(1, 1).varint(2, 42).bytes(3, entry).bytes(3, entry).toByteArray()
        val m = Proto.decode(resp)
        assertEquals(1, m.int(1))
        assertEquals(42, m.int(2))
        val entries = m.messages(3)
        assertEquals(2, entries.size)
        assertEquals("Redmi", entries[0].string(1))
        assertEquals(0x0A, entries[1].int(5))
        // repeated varints (register_live_stream_status = [1, 2, 4])
        val reg = Proto.decode(Proto.Writer().varint(1, 1).varint(1, 2).varint(1, 4).toByteArray())
        assertEquals(listOf(1L, 2L, 4L), reg.longs(1))
    }

    @Test
    fun fragmentAndReassemble() {
        for (size in listOf(0, 1, 17, 18, 19, 20, 37, 100, 300)) {
            val msg = ByteArray(size) { (it * 7).toByte() }
            val packets = GoProBle.fragment(msg)
            assertEquals((0x20 or (size shr 8)).toByte(), packets[0][0])
            assertEquals(size.toByte(), packets[0][1])
            for (p in packets) assert(p.size <= 20)
            for (p in packets.drop(1)) assertEquals(0x80.toByte(), p[0])
            val acc = GoProBle.Accumulator()
            var out: ByteArray? = null
            for (p in packets) {
                val r = acc.feed(p)
                if (r != null) out = r
            }
            assertArrayEquals("size $size", msg, out ?: ByteArray(0))
        }
    }

    @Test
    fun reassembleGeneralHeaderFromCamera() {
        // Camera-style 5-bit header: 3 bytes [cmd id, status, value]
        val acc = GoProBle.Accumulator()
        assertArrayEquals(byteArrayOf(0x3C, 0x00, 0x42), acc.feed(byteArrayOf(0x03, 0x3C, 0x00, 0x42)))
        // and a 16-bit extended header split over two packets
        val big = ByteArray(25) { it.toByte() }
        assertNull(acc.feed(byteArrayOf(0x40, 0x00, 25) + big.copyOfRange(0, 17)))
        assertArrayEquals(big, acc.feed(byteArrayOf(0x80.toByte()) + big.copyOfRange(17, 25)))
    }
}
