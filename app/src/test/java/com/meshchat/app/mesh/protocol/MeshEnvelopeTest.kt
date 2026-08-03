package com.meshchat.app.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshEnvelopeTest {
    private val text = MeshEnvelope(
        id = "msg-1", kind = "TEXT", srcId = "A001", dstId = "B002",
        convId = "conv-A001-B002", ttl = 8, ts = 1700000000000,
        body = TextBody("你好"),
    )

    private val file = MeshEnvelope(
        id = "msg-2", kind = "FILE", srcId = "A001", dstId = "B002",
        convId = "conv-A001-B002", ttl = 8, ts = 1700000000001,
        body = FileBody("a.jpg", "image/jpeg", 40960, 2, 0, "BASE64=="),
    )

    private val group = MeshEnvelope(
        id = "msg-3", kind = "GROUP", srcId = "A001", dstId = "g-1",
        convId = "g-1", ttl = 8, ts = 1700000000002,
        body = GroupBody(op = "JOIN", groupName = "营地"),
    )

    @Test
    fun `text envelope roundtrip`() {
        assertEquals(text, MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(text)))
    }

    @Test
    fun `file envelope roundtrip`() {
        assertEquals(file, MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(file)))
    }

    @Test
    fun `group envelope roundtrip`() {
        assertEquals(group, MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(group)))
    }

    @Test
    fun `decoding resolves correct body type`() {
        val decoded = MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(file))
        assertEquals(FileBody::class, decoded.body::class)
    }
}
