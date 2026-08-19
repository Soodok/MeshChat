package com.meshchat.app.security.lock

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v1.1.58 应用锁：
 * - 设密码：PBKDF2 派生 KEK → AES-GCM 加密 DEK 存两份——密码版（KEK）与指纹版（AndroidKeyStore 生物认证密钥）。
 * - 解锁：密码验证（解出 DEK）或指纹（BiometricPrompt 认证成功后用认证密钥解出 DEK）。
 * - DEK（内存，进程级一次）供敏感密钥库（E2EE 会话/群密钥）加解密——设密码后密钥库密文存储。
 * - locked 状态：回前台/手动锁定（DEK 保留内存，后台服务可继续工作）。
 * - v1.1.58 连续失败自动锁定：连续 5 次密码错误 → 锁定解锁入口 30s（防暴力破解）。
 */
class AppLockManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("meshchat_lock", Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** 当前是否锁定（需密码/指纹认证才可进入 UI）。 */
    val locked = MutableStateFlow(false)

    /** v1.1.58 连续失败锁定状态（UI 倒计时显示）。 */
    private val _lockout = MutableStateFlow(restoreLockout())
    val lockout: StateFlow<LockoutState> = _lockout.asStateFlow()

    /** 是否已设密码（响应式，设置页显示"已启用/设置密码"）。 */
    private val _passwordEnabled = MutableStateFlow(hasPassword)
    val passwordEnabled: StateFlow<Boolean> = _passwordEnabled.asStateFlow()

    /** v1.1.83 指纹版 DEK 副本是否存在（响应式，设置页显示"密码+指纹已启用"；存在才是真启用了指纹）。 */
    private val _fingerprintEnabled = MutableStateFlow(hasFingerprintBlob)
    val fingerprintEnabled: StateFlow<Boolean> = _fingerprintEnabled.asStateFlow()

    /** 指纹版 DEK 副本是否缺失（缺失时 UI 应提示启用指纹）。 */
    val biometricBlobMissing: Boolean get() = !hasFingerprintBlob

    /** 解锁后持有的数据密钥（内存；未设密码时为 null——密钥库保持明文）。 */
    @Volatile private var dek: ByteArray? = null

    val hasPassword: Boolean get() = prefs.contains(KEY_DEK_BY_PWD)

    /** 指纹版 DEK 副本是否存在（指纹解锁可用性的真实判据）。 */
    val hasFingerprintBlob: Boolean get() = prefs.contains(KEY_DEK_BY_BIO)

    /** 设备是否支持生物识别（指纹/面容）——供 UI 显示指纹按钮。 */
    fun biometricAvailable(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = context.getSystemService(BiometricManager::class.java)
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            android.hardware.fingerprint.FingerprintManager::class.java
                .let { context.getSystemService(it) }
                ?.isHardwareDetected == true
        }
    }.getOrDefault(false)

    /** 当前数据密钥（解锁后可用；未设密码 = null）。 */
    fun dek(): ByteArray? = dek

    /**
     * 设置/重置密码：生成新 DEK，同时写密码版与指纹版两份密文。
     * v1.1.83 指纹版依赖 keystore：部分 ROM/模拟器（keystore2）对 setUserAuthenticationRequired 密钥连加密也要求认证 token，
     * 直接加密失败则指纹副本留空 → UI 应随后通过 BiometricPrompt 认证（finishBiometricEnrollAfterAuth）补写。
     */
    fun setPassword(password: String) {
        if (password.length < 4) throw IllegalArgumentException("密码至少 4 位")
        val newDek = LockCrypto.randomDek()
        val salt = ByteArray(LockCrypto.SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val kek = LockCrypto.deriveKek(password, salt)
        val blobByPwd = LockCrypto.encryptDek(kek, newDek)
        val blobByBio = runCatching { encryptWithBiometricKey(newDek) }.getOrNull()   // 无生物识别/设备锁屏/加密需认证 → 指纹副本为空，由 UI 认证后补写
        prefs.edit()
            .putString(KEY_SALT, Base64.getEncoder().encodeToString(salt))
            .putString(KEY_DEK_BY_PWD, blobByPwd)
            .putString(KEY_DEK_BY_BIO, blobByBio)
            .apply()
        dek = newDek
        locked.value = false
        clearLockout()
        _passwordEnabled.value = true
        _fingerprintEnabled.value = blobByBio != null
        Log.i(TAG, "app lock password set (DEK rotated, fingerprintBlob=${blobByBio != null})")
    }

    /** 修改密码：验证旧密码后仅重写密码版（DEK 不变 → 密钥库无需迁移）。 */
    fun changePassword(oldPassword: String, newPassword: String): Boolean {
        if (!verifyPassword(oldPassword)) return false
        setPassword(newPassword)
        return true
    }

    /** 密码解锁：解出 DEK 并解锁；密码错误返回 false。v1.1.58 失败计数 + 连续 5 次锁定 30s。 */
    fun verifyPassword(password: String): Boolean {
        if (isLockedOut()) return false
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val blob = prefs.getString(KEY_DEK_BY_PWD, null) ?: return false
        val salt = Base64.getDecoder().decode(saltB64)
        val kek = LockCrypto.deriveKek(password, salt)
        val d = LockCrypto.decryptDek(kek, blob)
        if (d == null) {
            recordFailure()
            return false
        }
        dek = d
        locked.value = false
        clearLockout()
        // 密码解锁成功即刷新指纹副本：修复 ① 设密码时设备锁屏致指纹副本为空 ② 新录入指纹致旧生物密钥失效。
        // 真机（加密无需认证）自动补写成功；keystore2 强制认证的设备由 UI 在解锁后弹"启用指纹"认证补写。
        refreshBiometricBlob(d)
        return true
    }

    /**
     * v1.1.75 指纹解锁（认证成功后解密）：BiometricPrompt 认证成功回调里调用。
     * 认证成功 → keystore 解锁生物密钥 → init + doFinal 必然成功。
     * 不依赖认证前 init（华为/部分 ROM 上 setUserAuthenticationRequired 密钥在认证前 init 会被拒 → 原方案"指纹不可用"）。
     * v1.1.63 自修复：blob 解密失败（新录指纹致旧生物密钥失效/旧 blob 不匹配）时，
     * 若内存已有 DEK（本会话曾密码解锁，DEK 锁定后保留）→ 用当前生物密钥重建指纹副本并解锁。
     * v1.1.75 关键修复：指纹认证必须携带 CryptoObject（prepareBiometricSession 的 cipher）——
     * keystore 生物密钥（setUserAuthenticationRequired）只有经 BiometricPrompt 认证后才被授权，
     * 传 null CryptoObject 认证成功也无法解密（doFinal 抛 UserNotAuthenticatedException）→ 指纹"无效"。
     * v1.1.82 参考官方（android/security-samples）：优先使用认证结果 result.cryptoObject.cipher 直接 doFinal——
     * 该 cipher 就是 prepareBiometricSession 的会话 cipher（同一实例），但经认证结果取回不依赖 UI 状态捕获，
     * 回调时序/Compose 重组下更可靠。
     */
    fun finishBiometricUnlockAfterAuth(
        session: BiometricAuthSession? = null,
        authenticatedCipher: Cipher? = null,
    ): Boolean {
        if (isLockedOut()) return false
        val blob = prefs.getString(KEY_DEK_BY_BIO, null) ?: return false
        val d = when {
            // v1.1.82 认证结果携带的 cipher（已用 blob IV init 解密模式，认证后 doFinal 被 keystore 授权）
            authenticatedCipher != null -> runCatching {
                val raw = Base64.getDecoder().decode(blob)
                val ct = raw.copyOfRange(LockCrypto.GCM_IV_BYTES, raw.size)
                authenticatedCipher.doFinal(ct)
            }.getOrNull()
            session != null -> {
                runCatching { session.doFinal() }.getOrNull()
            }
            else -> {
                // 兼容旧调用/无会话兜底：直接解密（未授权时大概率失败 → 落自修复）
                runCatching {
                    val raw = Base64.getDecoder().decode(blob)
                    val iv = raw.copyOfRange(0, LockCrypto.GCM_IV_BYTES)
                    val ct = raw.copyOfRange(LockCrypto.GCM_IV_BYTES, raw.size)
                    val key = biometricKey()
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(LockCrypto.GCM_TAG_BITS, iv))
                    cipher.doFinal(ct)
                }.getOrNull()
            }
        }
        if (d != null) {
            dek = d
            locked.value = false
            clearLockout()
            return true
        }
        // 指纹副本失效（新录指纹/旧 blob）：用内存 DEK 重建指纹副本自修复（无内存 DEK 则提示用密码解锁一次）
        val currentDek = dek ?: return false
        return runCatching {
            val newBlob = encryptWithBiometricKey(currentDek)
            prefs.edit().putString(KEY_DEK_BY_BIO, newBlob).apply()
            dek = currentDek
            locked.value = false
            clearLockout()
            true
        }.getOrDefault(false)
    }

    /**
     * v1.1.75 准备指纹解锁会话：读取指纹副本的 IV 并 init 解密模式 Cipher（绑定生物密钥）。
     * 该 Cipher 必须作为 BiometricPrompt 的 CryptoObject 认证——认证成功后 doFinal 才被 keystore 授权。
     * null = 无指纹副本/密钥失效（新录指纹未重建）/设备无生物识别 → UI 提示用密码解锁一次后自动重建。
     */
    fun prepareBiometricSession(): BiometricAuthSession? = runCatching {
        val blob = prefs.getString(KEY_DEK_BY_BIO, null) ?: return null
        val raw = Base64.getDecoder().decode(blob)
        val iv = raw.copyOfRange(0, LockCrypto.GCM_IV_BYTES)
        val ct = raw.copyOfRange(LockCrypto.GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, biometricKey(), GCMParameterSpec(LockCrypto.GCM_TAG_BITS, iv))
        BiometricAuthSession(cipher, ct)
    }.getOrNull()

    /**
     * v1.1.83 准备"启用指纹"会话：ENCRYPT_MODE init 绑定生物密钥（init 无需认证 token）。
     * 该 Cipher 必须作为 BiometricPrompt 的 CryptoObject 认证——认证成功后 doFinal 加密 DEK 才被 keystore 授权
     * （官方 android/security-samples 标准：keystore2 上 setUserAuthenticationRequired 密钥连加密也要认证 token，
     * 模拟器/部分 ROM 直接加密必报 KEY_USER_NOT_AUTHENTICATED → 指纹副本永远建不起来）。
     * null = 无生物识别 / 无内存 DEK。
     */
    fun prepareBiometricEnrollSession(): BiometricEnrollSession? = runCatching {
        if (!biometricAvailable()) return null
        if (dek == null) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, biometricKey())
        BiometricEnrollSession(cipher)
    }.getOrNull()

    /**
     * v1.1.83 指纹认证成功后补写指纹副本：用已授权 cipher（认证结果携带，优先）加密内存 DEK 存 KEY_DEK_BY_BIO。
     * 覆盖设密码时指纹副本为空的设备（模拟器 keystore2 / 部分 ROM）。返回是否成功。
     */
    fun finishBiometricEnrollAfterAuth(
        session: BiometricEnrollSession? = null,
        authenticatedCipher: Cipher? = null,
    ): Boolean {
        val d = dek ?: return false
        val cipher = authenticatedCipher ?: session?.cipher ?: return false
        return runCatching {
            val ct = cipher.doFinal(d)
            val blob = Base64.getEncoder().encodeToString(cipher.iv + ct)
            prefs.edit().putString(KEY_DEK_BY_BIO, blob).apply()
            _fingerprintEnabled.value = true
            Log.i(TAG, "fingerprint blob enrolled after auth")
            true
        }.getOrDefault(false)
    }

    /** 移除密码（恢复无锁）：清空锁数据与 DEK（已加密的密钥库条目随之不可解——明文回退）。 */
    fun removePassword() {
        prefs.edit().remove(KEY_SALT).remove(KEY_DEK_BY_PWD).remove(KEY_DEK_BY_BIO).apply()
        dek = null
        locked.value = false
        clearLockout()
        _passwordEnabled.value = false
        _fingerprintEnabled.value = false
    }

    /** 锁定 UI（回前台/手动）：DEK 保留内存，后台服务继续工作。 */
    fun lock() { locked.value = true }

    // ===== 连续失败自动锁定（v1.1.58）=====

    fun isLockedOut(now: Long = System.currentTimeMillis()): Boolean =
        _lockout.value.lockoutUntilMs > now

    /** 剩余锁定毫秒数（>0 时 UI 显示倒计时并禁用解锁）。 */
    fun remainingLockoutMs(now: Long = System.currentTimeMillis()): Long =
        (_lockout.value.lockoutUntilMs - now).coerceAtLeast(0)

    private fun recordFailure() {
        val now = System.currentTimeMillis()
        val nf = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        if (nf >= MAX_FAILURES) {
            val until = now + LOCKOUT_MS
            prefs.edit().remove(KEY_FAIL_COUNT).putLong(KEY_LOCKOUT_UNTIL, until).apply()
            _lockout.value = LockoutState(failCount = 0, lockoutUntilMs = until)
            Log.w(TAG, "app lock: $nf failures -> locked out ${LOCKOUT_MS}ms")
        } else {
            prefs.edit().putInt(KEY_FAIL_COUNT, nf).apply()
            _lockout.value = LockoutState(failCount = nf, lockoutUntilMs = 0)
        }
    }

    private fun clearLockout() {
        prefs.edit().remove(KEY_FAIL_COUNT).remove(KEY_LOCKOUT_UNTIL).apply()
        _lockout.value = LockoutState()
    }

    private fun restoreLockout(): LockoutState {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        val now = System.currentTimeMillis()
        return if (until > now) LockoutState(failCount = 0, lockoutUntilMs = until)
        else LockoutState(failCount = prefs.getInt(KEY_FAIL_COUNT, 0).coerceAtMost(MAX_FAILURES - 1), lockoutUntilMs = 0)
    }

    // ===== 指纹版 DEK（AndroidKeyStore 生物认证密钥）=====

    private fun biometricKey(): SecretKey {
        val existing = runCatching { keyStore.getKey(BIO_ALIAS, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing
        // alias 存在但密钥失效（新录入指纹 setInvalidatedByBiometricEnrollment）/损坏 → 删除重建
        if (keyStore.containsAlias(BIO_ALIAS)) keyStore.deleteEntry(BIO_ALIAS)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)          // 仅生物认证后可解锁使用
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
        }.generateKey()
    }

    /** 密码解锁成功后刷新指纹副本（设备此刻必处于解锁状态——能输 App 密码）。v1.1.83 返回是否成功并同步状态。 */
    private fun refreshBiometricBlob(dek: ByteArray): Boolean {
        if (!biometricAvailable()) return false
        return runCatching {
            val newBlob = encryptWithBiometricKey(dek)
            prefs.edit().putString(KEY_DEK_BY_BIO, newBlob).apply()
            _fingerprintEnabled.value = true
            true
        }.getOrElse {
            Log.w(TAG, "refresh biometric blob failed", it)
            false
        }
    }

    private fun encryptWithBiometricKey(dek: ByteArray): String {
        val key = biometricKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(dek)
        return Base64.getEncoder().encodeToString(cipher.iv + ct)
    }

    private companion object {
        const val TAG = "AppLock"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val BIO_ALIAS = "meshchat_lock_biometric"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SALT = "lock_salt"
        const val KEY_DEK_BY_PWD = "lock_dek_by_pwd"
        const val KEY_DEK_BY_BIO = "lock_dek_by_bio"
        const val KEY_FAIL_COUNT = "lock_fail_count"
        const val KEY_LOCKOUT_UNTIL = "lock_lockout_until"

        /** v1.1.58 连续失败自动锁定：5 次失败锁定 30s（防暴力破解）。 */
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 30_000L
    }
}

/** 连续失败锁定状态。 */
data class LockoutState(
    val failCount: Int = 0,
    val lockoutUntilMs: Long = 0L,
)

/**
 * v1.1.75 指纹解锁会话：Cipher 已绑定生物密钥并含 IV（解密模式），ct 为待解密密文。
 * 认证成功（BiometricPrompt 携带 cipher 作为 CryptoObject）后调用 doFinal 完成解密。
 */
class BiometricAuthSession internal constructor(
    val cipher: Cipher,
    private val ct: ByteArray,
) {
    fun doFinal(): ByteArray = cipher.doFinal(ct)
}

/**
 * v1.1.83 启用指纹会话：Cipher 已绑定生物密钥（加密模式，init 无需认证）。
 * 认证成功（BiometricPrompt 携带 cipher 作为 CryptoObject）后，由 finishBiometricEnrollAfterAuth 用其加密 DEK 存指纹副本。
 */
class BiometricEnrollSession internal constructor(
    val cipher: Cipher,
)
