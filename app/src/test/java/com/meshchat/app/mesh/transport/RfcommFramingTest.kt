package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshFrame
import java.io.PipedInputStream
import java.io.PipedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RfcommFramingTest {
    private fun pipePair(): Pair<PipedInputStream, PipedOutputStream> {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 8192)
        return input to out
    }

    @Test
    fun `frames roundtrip through stream`() {
        val (input, out) = pipePair()
        val frames = listOf(
            MeshFrame(FrameType.DATA, "{\"a\":1}".toByteArray()),
            MeshFrame(FrameType.DATA, ByteArray(300) { 7 }),
            MeshFrame(FrameType.RECEIPT, "x".toByteArray()),
        )
        frames.forEach { RfcommFraming.writeFrame(out, it) }
        frames.forEach { assertEquals(it, RfcommFraming.readFrame(input)) }
    }

    @Test
    fun `returns null when stream closed between frames`() {
        val (input, out) = pipePair()
        RfcommFraming.writeFrame(out, MeshFrame(FrameType.DATA, "abc".toByteArray()))
        out.close()
        val first = RfcommFraming.readFrame(input)
        assertEquals("abc", first?.payloadText)
        assertNull(RfcommFraming.readFrame(input))
    }

    @Test
    fun `empty stream returns null`() {
        val (input, out) = pipePair()
        out.close()
        assertNull(RfcommFraming.readFrame(input))
    }
}
