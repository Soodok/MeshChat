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
import com.meshchat.app.security.lock.BiometricUnlockRequest
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
    onCreateBiometricUnlock: () -> BiometricUnlockRequest?,
    onUnlockWithBiometric: (BiometricUnlockRequest) -> Boolean,
    onRemainingLockoutMs: () -> Long,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showBioError by remember { mutableStateOf(false) }
    var lastBiometricRequest by remember { mutableStateOf<BiometricUnlockRequest?>(null) }
    var biometricPrompt by remember { mutableStateOf<BiometricPrompt?>(null) }

    // 锁定倒计时轮询（0.5s 刷新）
    val lockoutRemaining = remember { mutableLongStateOf(0L) }
    LaunchedEffect(lockout.lockoutUntilMs) {
        while (lockout.lockoutUntilMs > 0L) {
            lockoutRemaining.longValue = onRemainingLockoutMs()
            if (lockoutRemaining.longValue <= 0L) break
            delay(500)
        }
    }

    // BiometricPrompt 需要 FragmentActivity 宿主（MainActivity 已改基类）
    val activity = context as? FragmentActivity
    LaunchedEffect(activity) {
        if (activity == null) return@LaunchedEffect
        val executor = ContextCompat.getMainExecutor(context)
        biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val req = lastBiometricRequest
                    if (req != null) onUnlockWithBiometric(req)
                    else showBioError = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showBioError = true
                }

                override fun onAuthenticationFailed() {
                    showBioError = true
                }
            },
        )
    }

    fun unlockWithFingerprint() {
        val req = onCreateBiometricUnlock()
        if (req == null) {
            error = "生物识别暂不可用：请先解锁手机屏幕，或改用密码"
            return
        }
        lastBiometricRequest = req
        showBioError = false
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("指纹解锁 MeshChat")
                .setSubtitle("验证指纹以解锁应用")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
        } else {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("指纹解锁 MeshChat")
                .setSubtitle("验证指纹以解锁应用")
                .setNegativeButtonText("取消")
                .build()
        }
        biometricPrompt?.authenticate(info, BiometricPrompt.CryptoObject(req.cipher))
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
