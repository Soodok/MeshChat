package com.meshchat.app.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MeshFrameTest {
    @Test
    fun `frame roundtrip preserves type and payload`() {
        val frame = MeshFrame(FrameType.DATA, "hello".toByteArray())
        assertEquals(frame, MeshFrame.decode(frame.encode()))
    }

    @Test
    fun `unknown frame type throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(byteArrayOf(0x7F, 0x00, 0x00))
        }
    }

    @Test
    fun `length mismatch throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(byteArrayOf(0x02, 0x00, 0x0A, 0x01))
        }
    }
}
