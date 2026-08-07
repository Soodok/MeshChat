package com.meshchat.app.mesh.crypto

import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.1.57 E2EE 密钥存储抽象：
 * - 本机 ECDH 密钥对（私钥不可导出——Android 实现用 AndroidKeyStore）
 * - 对端会话密钥（peerId → 32B）与群密钥（groupId → 32B）持久化
 */
interface E2eeKeyStore {
    /** 本机密钥对（首次调用生成并保存）。 */
    fun localKeyPair(): KeyPair
    fun sessionKey(peerId: String): ByteArray?
    fun saveSessionKey(peerId: String, key: ByteArray)
    fun groupKey(groupId: String): ByteArray?
    fun saveGroupKey(groupId: String, key: ByteArray)
}

/** 内存实现（JVM 单测 / 默认构造）：密钥对用 JCA 生成，派生密钥/群密钥存 HashMap。每实例独立密钥对。 */
class InMemoryE2eeKeyStore : E2eeKeyStore {
    private val local by lazy { MeshCrypto.generateKeyPair() }
    private val sessions = ConcurrentHashMap<String, ByteArray>()
    private val groups = ConcurrentHashMap<String, ByteArray>()
    override fun localKeyPair(): KeyPair = local
    override fun sessionKey(peerId: String): ByteArray? = sessions[peerId]
    override fun saveSessionKey(peerId: String, key: ByteArray) { sessions[peerId] = key }
    override fun groupKey(groupId: String): ByteArray? = groups[groupId]
    override fun saveGroupKey(groupId: String, key: ByteArray) { groups[groupId] = key }
}
