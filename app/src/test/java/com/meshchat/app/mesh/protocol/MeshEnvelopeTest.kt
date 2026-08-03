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
        body = FileBody(
            fileId = "f-2", fileName = "a.jpg", mime = "image/jpeg",
            size = 40960, totalChunks = 2, chunkIndex = 0, chunkData = "BASE64==",
        ),
    )

    private val fileAck = MeshEnvelope(
        id = "msg-4", kind = "FILE_ACK", srcId = "B002", dstId = "A001",
        convId = "conv-A001-B002", ttl = 8, ts = 1700000000003,
        body = FileAckBody(fileId = "f-2", totalChunks = 2, missing = listOf(1)),
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

    @Test
    fun `file body roundtrip keeps fileId and chunk fields`() {
        val decoded = MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(file))
        val body = decoded.body as FileBody
        assertEquals("f-2", body.fileId)
        assertEquals("a.jpg", body.fileName)
        assertEquals(2, body.totalChunks)
        assertEquals(0, body.chunkIndex)
        assertEquals("BASE64==", body.chunkData)
    }

    @Test
    fun `file ack body roundtrip keeps missing list`() {
        val decoded = MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(fileAck))
        val body = decoded.body as FileAckBody
        assertEquals("f-2", body.fileId)
        assertEquals(2, body.totalChunks)
        assertEquals(listOf(1), body.missing)
    }
}
