package com.meshchat.app.ui.screens

import android.os.Build
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meshchat.app.security.local.AndroidLocalSecuritySignalCollector
import com.meshchat.app.security.lock.LockoutState
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Divider
import com.meshchat.app.ui.theme.Ink
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.MeshAmber
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.MeshRed
import com.meshchat.app.ui.theme.TextPrimary
import com.meshchat.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * v1.1.58 应用锁解锁屏：覆盖主 UI，密码/指纹二选一解锁。
 * - 连续 5 次密码错误 → 锁定解锁入口 30s（倒计时显示）。
 * - 检测到无障碍服务/调试器/可调试构建 → 防自动化警告条。
 */
@Composable
fun AppLockScreen(
    biometricAvailable: Boolean,
    lockout: LockoutState,
    onVerifyPassword: (String) -> Boolean,
    /** v1.1.75 准备指纹解锁会话（Cipher+密文）；null = 指纹数据不可用（提示用密码解锁一次）。 */
    onPrepareBiometricSession: () -> com.meshchat.app.security.lock.BiometricAuthSession?,
    /** v1.1.75 指纹认证成功后用已授权会话解密 DEK。 */
    onFinishBiometricUnlock: (com.meshchat.app.security.lock.BiometricAuthSession?) -> Boolean,
    onRemainingLockoutMs: () -> Long,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showBioError by remember { mutableStateOf(false) }

    // 锁定倒计时轮询（0.5s 刷新）
    val lockoutRemaining = remember { mutableLongStateOf(0L) }
    LaunchedEffect(lockout.lockoutUntilMs) {
        while (lockout.lockoutUntilMs > 0L) {
            lockoutRemaining.longValue = onRemainingLockoutMs()
            if (lockoutRemaining.longValue <= 0L) break
            delay(500)
        }
    }

    // v1.1.75 当前指纹解锁会话（Cipher 已绑定生物密钥）：认证成功回调用它解密 DEK
    var activeSession by remember { mutableStateOf<com.meshchat.app.security.lock.BiometricAuthSession?>(null) }

    // v1.1.62 指纹认证改用系统 API：android.hardware.biometrics.BiometricPrompt（API 30+ 才公开 Builder）。
    // 原 androidx.biometric 兼容层在部分设备（华为/部分 ROM）点击 authenticate 抛异常 → 主线程崩溃卡退；
    // 系统 API 直接弹系统认证对话框、无 Fragment 依赖，且 authenticate 全链路 runCatching 兜底。
    // 注：系统 BiometricPrompt 构造器为 @hide（package-private），公开用法 = Builder.build() + authenticate(callback,...)。
    //     API 28/29 系统 API 仍不可用（Builder 无 setAllowedAuthenticators），只能走 androidx。
    val biometricCallback = remember(context) {
        object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                // v1.1.75 认证成功 → keystore 已授权生物密钥 → 用会话 cipher 解密 DEK（CryptoObject 必须非 null）
                if (!onFinishBiometricUnlock(activeSession)) {
                    showBioError = true
                    com.meshchat.app.mesh.debug.DebugLogBuffer.log("AppLock", "指纹认证成功但 DEK 解密失败")
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                com.meshchat.app.mesh.debug.DebugLogBuffer.log("AppLock", "指纹认证错误 code=$errorCode $errString")
                error = "指纹认证被中断（$errString），请重试或使用密码"
            }

            override fun onAuthenticationFailed() {
                showBioError = true
            }
        }
    }
    val systemBiometricPrompt: android.hardware.biometrics.BiometricPrompt? = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.hardware.biometrics.BiometricPrompt.Builder(context)
                .setTitle("指纹解锁 MeshChat")
                .setSubtitle("验证指纹以解锁应用")
                // 强生物识别 + 设备凭据兜底（有指纹用指纹；无指纹可 PIN/密码，避免"无匹配认证器"异常）
                .setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
        } else null
    }

    // API 26/27/28/29（无公开系统 BiometricPrompt）兜底：androidx 兼容层（需 FragmentActivity 宿主）
    val androidxCallback = remember(context) {
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                if (!onFinishBiometricUnlock(activeSession)) showBioError = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                showBioError = true
            }

            override fun onAuthenticationFailed() {
                showBioError = true
            }
        }
    }
    val activity = context as? FragmentActivity
    var androidxPrompt by remember { mutableStateOf<androidx.biometric.BiometricPrompt?>(null) }
    LaunchedEffect(activity) {
        if (activity == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return@LaunchedEffect
        androidxPrompt = androidx.biometric.BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            androidxCallback,
        )
    }

    fun unlockWithFingerprint() {
        showBioError = false
        error = null
        // v1.1.75 必须携带 CryptoObject 认证：先准备会话（Cipher 已 init 解密模式并绑定生物密钥），
        // 认证成功后 keystore 授权该 cipher，doFinal 解密 DEK 才不抛异常（原 null CryptoObject → 指纹永远无效）
        val session = onPrepareBiometricSession()
        if (session == null) {
            error = "指纹数据不可用，请先用密码解锁一次（将自动重新录入指纹）"
            return
        }
        activeSession = session
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && systemBiometricPrompt != null) {
            runCatching {
                systemBiometricPrompt.authenticate(
                    android.hardware.biometrics.BiometricPrompt.CryptoObject(session.cipher),
                    android.os.CancellationSignal(),          // 不主动取消认证
                    ContextCompat.getMainExecutor(context),   // 回调线程
                    biometricCallback,
                )
            }.onFailure { t ->
                android.util.Log.e(TAG, "system biometric authenticate failed", t)
                com.meshchat.app.mesh.debug.DebugLogBuffer.log("AppLock", "系统指纹认证启动失败（${t.message ?: t.javaClass.simpleName}）")
                error = "指纹认证启动失败（${t.message ?: t.javaClass.simpleName}），请用密码解锁"
            }
        } else {
            // API 26-29 兜底
            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("指纹解锁 MeshChat")
                .setSubtitle("验证指纹以解锁应用")
                .setNegativeButtonText("取消")
                .build()
            runCatching {
                // 项目 androidx.biometric 为旧版 API：回调经构造器注入，这里只需 PromptInfo + CryptoObject
                androidxPrompt?.authenticate(
                    info,
                    androidx.biometric.BiometricPrompt.CryptoObject(session.cipher),
                )
            }.onFailure { t ->
                android.util.Log.e(TAG, "androidx biometric authenticate failed", t)
                com.meshchat.app.mesh.debug.DebugLogBuffer.log("AppLock", "androidx 指纹认证启动失败（${t.message ?: t.javaClass.simpleName}）")
                error = "指纹认证启动失败（${t.message ?: t.javaClass.simpleName}），请用密码解锁"
            }
        }
    }

    // v1.1.75 优先指纹：进入锁屏自动发起生物认证（无需点击），密码输入框仍可用作兜底
    LaunchedEffect(Unit) {
        if (biometricAvailable && onRemainingLockoutMs() <= 0L) {
            delay(400)   // 等界面与系统认证 UI 就绪
            unlockWithFingerprint()
        }
    }

    fun unlockWithPassword() {
        if (password.length < 4) {
            error = "密码至少 4 位"
            return
        }
        focusManager.clearFocus()
        val ok = onVerifyPassword(password)
        if (ok) {
            password = ""
            error = null
        } else {
            error = if (lockout.failCount > 0) "密码错误（已错 ${lockout.failCount}/${MAX_FAILURES} 次，连续错误将锁定 30 秒）" else "密码错误"
        }
    }

    // 防自动化警告（无障碍/调试器/可调试构建）——本地只读信号，无网络
    val automationWarnings = remember {
        runCatching {
            AndroidLocalSecuritySignalCollector(context).collect(System.currentTimeMillis())
        }.getOrDefault(emptyList()).mapNotNull { s ->
            when (s.ruleId) {
                "debugger-attached" -> "检测到调试器连接——自动化/破解工具可能已附着"
                "accessibility-service-enabled" -> "检测到无障碍服务开启——自动化工具可借此模拟操作"
                "debuggable-build-enabled" -> "当前为可调试构建——调试通道已打开"
                else -> null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .imePadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        Box(
            Modifier.size(64.dp).background(InkSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Lock, null, tint = Cyan, modifier = Modifier.size(30.dp))
        }
        Text(
            "MeshChat 已锁定",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            "输入密码或使用指纹解锁",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (lockoutRemaining.longValue > 0L) {
            Text(
                "尝试次数过多，请 ${(lockoutRemaining.longValue + 999) / 1000} 秒后再试",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshRed,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it.take(64)
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = lockoutRemaining.longValue <= 0L,
                singleLine = true,
                label = { Text("解锁密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { unlockWithPassword() }),
            )
            if (error != null) {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshAmber,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Button(
                onClick = { unlockWithPassword() },
                enabled = lockoutRemaining.longValue <= 0L && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) {
                Text("解锁")
            }
            if (biometricAvailable) {
                OutlinedButton(
                    onClick = { unlockWithFingerprint() },
                    enabled = lockoutRemaining.longValue <= 0L,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                ) {
                    Icon(Icons.Outlined.Fingerprint, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    Text("指纹解锁", modifier = Modifier.padding(start = 8.dp))
                }
                if (showBioError) {
                    Text(
                        "指纹验证未通过，请重试或使用密码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshAmber,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                Text(
                    "未检测到可用的指纹：设备未录入指纹或系统不支持，请用密码解锁（可在系统设置中录入指纹后重试）",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Divider)
            Spacer(Modifier.height(14.dp))
            Text(
                "后台服务与消息收发不受锁定影响 · 连续 5 次密码错误将锁定 30 秒",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        if (automationWarnings.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkSoft, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Text(
                    "安全警告",
                    style = MaterialTheme.typography.titleSmall,
                    color = MeshAmber,
                    fontWeight = FontWeight.Bold,
                )
                automationWarnings.forEach { w ->
                    Text(
                        "· $w",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshAmber,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(MeshGreen, CircleShape))
            Text(
                "后台服务运行中 · 消息仍可收发",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private const val MAX_FAILURES = 5
private const val TAG = "AppLockScreen"
