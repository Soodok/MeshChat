package com.meshchat.app.mesh.wifidirect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectFramingTest {
    @Test fun `register frame round trips`() {
        val bytes = WifiDirectFraming.encodeRegister("AB12", "192.168.49.5", 9876, "节点AB12")
        val info = WifiDirectFraming.decodeRegister(bytes)
        assertEquals("AB12", info?.shortId)
        assertEquals("192.168.49.5", info?.ip)
        assertEquals(9876, info?.port)
        assertEquals("节点AB12", info?.name)
    }

    @Test fun `data frame wrap and unwrap`() {
        val payload = ByteArray(100) { it.toByte() }
        val wrapped = WifiDirectFraming.wrapData(payload)
        assertArrayEquals(payload, WifiDirectFraming.unwrapData(wrapped))
    }

    @Test fun `malformed register returns null`() {
        assertNull(WifiDirectFraming.decodeRegister(byteArrayOf(1, 2, 3)))
        assertNull(WifiDirectFraming.decodeRegister(byteArrayOf()))
    }

    @Test fun `malformed data frame returns null`() {
        assertNull(WifiDirectFraming.unwrapData(byteArrayOf(0x4D)))
        assertNull(WifiDirectFraming.unwrapData(byteArrayOf(0x4D, 0x44, 0x00)))
    }

    @Test fun `large payload within max size round trips`() {
        val payload = ByteArray(WifiDirectFraming.MAX_PAYLOAD) { (it % 251).toByte() }
        val wrapped = WifiDirectFraming.wrapData(payload)
        assertArrayEquals(payload, WifiDirectFraming.unwrapData(wrapped))
    }

    @Test fun `data frame is not parsed as register`() {
        assertNull(WifiDirectFraming.decodeRegister(WifiDirectFraming.wrapData(ByteArray(8))))
    }
}
