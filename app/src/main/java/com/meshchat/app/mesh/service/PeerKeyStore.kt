package com.meshchat.app.mesh.service

import android.content.Context

/**
 * v1.1.74 对端公钥指纹持久化（密钥连续性 TOFU，MITM 防御）：
 * 首次握手记录对端公钥指纹，之后变化 → 标记身份变更（红色告警）。
 */
interface PeerKeyStore {
    /** 首次握手记录的对端公钥指纹；无记录返回 null。 */
    fun fingerprint(peerId: String): String?

    fun saveFingerprint(peerId: String, fp: String)

    /** v1.1.79 清除指纹记录（拉黑时双方清除）——下次重新握手重新 TOFU（指纹重立）。 */
    fun remove(peerId: String)
}

/** 测试/未注入时的默认空实现（不记录 → 永不触发身份变更告警）。 */
object NoopPeerKeyStore : PeerKeyStore {
    override fun fingerprint(peerId: String): String? = null
    override fun saveFingerprint(peerId: String, fp: String) {}
    override fun remove(peerId: String) {}
}

/** SharedPreferences 实现：指纹存 meshchat_e2ee（与 E2EE 密钥同库），键 fp_<peerId>。 */
class SharedPrefsPeerKeyStore(context: Context) : PeerKeyStore {
    private val prefs = context.getSharedPreferences("meshchat_e2ee", Context.MODE_PRIVATE)
    private fun key(peerId: String) = "fp_$peerId"

    override fun fingerprint(peerId: String): String? = prefs.getString(key(peerId), null)

    override fun saveFingerprint(peerId: String, fp: String) {
        prefs.edit().putString(key(peerId), fp).apply()
    }

    override fun remove(peerId: String) {
        prefs.edit().remove(key(peerId)).apply()
    }
}
