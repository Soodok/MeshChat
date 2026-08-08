package com.meshchat.app.mesh.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFingerprintTest {
    @Test
    fun `same channel name produces stable fingerprint`() {
        assertEquals(ChannelFingerprint.of("mesh-team"), ChannelFingerprint.of("mesh-team"))
    }

    @Test
    fun `different channel names produce different fingerprints`() {
        assertNotEquals(ChannelFingerprint.of("mesh-team"), ChannelFingerprint.of("other-team"))
    }

    @Test
    fun `fingerprint fits 6 bytes and never collides with public channel sentinel`() {
        val fp = ChannelFingerprint.of("mesh-team")
        assertTrue("指纹必须 > 0（0 保留给公共频道）", fp > 0)
        assertTrue("指纹必须 ≤ 2^48-1（6 字节截断）", fp < (1L shl 48))
    }
}
