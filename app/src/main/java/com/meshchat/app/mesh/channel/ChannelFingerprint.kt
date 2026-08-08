package com.meshchat.app.mesh.channel

import java.security.MessageDigest

/**
 * 频道指纹（v1.1.66）：SHA-256("meshchat-channel-v1:" + 频道名) 前 6 字节 → Long（0 ~ 2^48-1）。
 * - 0 为保留值（公共频道/未知/老版本设备）；of() 若巧合算出 0（概率 2^-48）则返回 1，避免被当成公共。
 * - 单向哈希 + 48 位截断：广播只携带指纹，嗅探者无法从包内容反推频道名，字典攻击不可靠。
 */
object ChannelFingerprint {
    fun of(name: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("meshchat-channel-v1:$name".toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 6) v = (v shl 8) or (digest[i].toLong() and 0xFF)
        return if (v == 0L) 1L else v
    }
}
