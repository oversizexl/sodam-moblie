package org.sodahub.sodam.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sodam_ffi.AccountInfo
import uniffi.sodam_ffi.LoginState
import uniffi.sodam_ffi.QrLogin
import uniffi.sodam_ffi.SodaClient
import uniffi.sodam_ffi.SodaException

private enum class LoginMethod { Qr, Cookie }

private sealed interface QrState {
    data object Creating : QrState
    data class Waiting(val qr: QrLogin, val message: String) : QrState
    data class SecondVerify(val token: String, val url: String, val message: String) : QrState
    data class Failed(val message: String) : QrState
}

private const val POLL_INTERVAL_MS = 2_000L

/**
 * 登录二级页：先选方式（扫码 / Cookie），成功后把账号交给宿主。
 * 返回按钮和系统返回手势都退回设置页。
 */
@Composable
fun LoginScreen(
    client: SodaClient,
    cookieFile: String,
    onLoggedIn: (AccountInfo) -> Unit,
    onBack: () -> Unit,
) {
    var method by remember { mutableStateOf<LoginMethod?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var qrState by remember { mutableStateOf<QrState>(QrState.Creating) }
    var cookieText by remember { mutableStateOf("") }
    var cookieBusy by remember { mutableStateOf(false) }
    var cookieError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    // Cookie 校验失败的提示 3 秒后自动消失。
    LaunchedEffect(cookieError) {
        if (cookieError.isNotEmpty()) {
            delay(3_000)
            cookieError = ""
        }
    }

    LaunchedEffect(method, attempt) {
        if (method != LoginMethod.Qr) return@LaunchedEffect
        qrState = QrState.Creating
        val qr = try {
            withContext(Dispatchers.IO) { client.createQr() }
        } catch (e: SodaException) {
            qrState = QrState.Failed("创建二维码失败：${e.readable()}")
            return@LaunchedEffect
        }
        qrState = QrState.Waiting(qr, "请用汽水音乐 App 扫码")
        pollQr(client, qr, cookieFile, { qrState = it }, onLoggedIn)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(
            "登录",
            onBack = { if (method == null) onBack() else method = null },
        )

        when (method) {
            null -> {
                Text(
                    "选择登录方式",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                Button(
                    onClick = {
                        attempt++
                        method = LoginMethod.Qr
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("扫码登录") }
                TextButton(
                    onClick = { method = LoginMethod.Cookie },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("输入 Cookie") }
            }

            LoginMethod.Qr -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                when (val s = qrState) {
                    QrState.Creating -> {
                        CircularProgressIndicator(Modifier.padding(top = 32.dp))
                        Text("正在生成二维码…", Modifier.padding(top = 12.dp))
                    }

                    is QrState.Waiting -> {
                        QrImage(s.qr.qrImage, Modifier.size(240.dp).padding(top = 24.dp))
                        Text(
                            s.message.ifBlank { "等待扫码…" },
                            modifier = Modifier.padding(top = 16.dp),
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = { attempt++ }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("重新生成")
                        }
                    }

                    is QrState.SecondVerify -> {
                        Text(
                            s.message.ifBlank { "请在下方完成验证" },
                            modifier = Modifier.padding(vertical = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                        VerifyWebView(s.url, Modifier.fillMaxWidth().weight(1f))
                    }

                    is QrState.Failed -> {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                        Button(onClick = { attempt++ }, modifier = Modifier.padding(top = 16.dp)) {
                            Text("重试")
                        }
                    }
                }
            }

            LoginMethod.Cookie -> Column {
                Text(
                    "粘贴汽水音乐网页版的完整 Cookie。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = cookieText,
                    onValueChange = { cookieText = it },
                    label = { Text("Cookie") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = {
                        if (cookieText.isBlank() || cookieBusy) return@Button
                        cookieBusy = true
                        cookieError = ""
                        val cookie = cookieText.trim()
                        scope.launch {
                            try {
                                val account = withContext(Dispatchers.IO) { client.verifyCookie(cookie) }
                                withContext(Dispatchers.IO) { writeCookie(cookieFile, cookie) }
                                onLoggedIn(account)
                            } catch (e: SodaException) {
                                cookieError = e.readable()
                            } finally {
                                cookieBusy = false
                            }
                        }
                    },
                    enabled = cookieText.isNotBlank() && !cookieBusy,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (cookieBusy) "验证中…" else "登录") }
                if (cookieError.isNotEmpty()) {
                    Text(
                        cookieError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

private suspend fun pollQr(
    client: SodaClient,
    qr: QrLogin,
    cookieFile: String,
    emit: (QrState) -> Unit,
    onLoggedIn: (AccountInfo) -> Unit,
) {
    var secondVerifyShown = false
    while (true) {
        delay(POLL_INTERVAL_MS)

        val progress = try {
            withContext(Dispatchers.IO) { client.checkQr(qr.token) }
        } catch (e: SodaException) {
            emit(QrState.Failed("轮询失败：${e.readable()}"))
            return
        }

        when (progress.state) {
            LoginState.NEED_SECOND_VERIFY -> {
                if (!secondVerifyShown) {
                    val url = runCatching {
                        withContext(Dispatchers.IO) { client.secondVerifyUrl(qr.token) }
                    }
                    url.fold(
                        onSuccess = {
                            emit(QrState.SecondVerify(qr.token, it, progress.message))
                            secondVerifyShown = true
                        },
                        onFailure = {
                            emit(QrState.Failed("打开验证页失败：${it.message}"))
                            return
                        },
                    )
                }
            }

            LoginState.SUCCESS -> {
                val account = try {
                    withContext(Dispatchers.IO) { client.verifyCookie(progress.cookie) }
                } catch (e: SodaException) {
                    emit(QrState.Failed("校验账号失败：${e.readable()}"))
                    return
                }
                withContext(Dispatchers.IO) { writeCookie(cookieFile, progress.cookie) }
                onLoggedIn(account)
                return
            }

            LoginState.EXPIRED -> {
                emit(QrState.Failed("二维码已过期，请重新生成"))
                return
            }

            LoginState.FAILED -> {
                emit(QrState.Failed(progress.message.ifBlank { "登录失败" }))
                return
            }

            LoginState.WAITING, LoginState.SCANNED -> {
                emit(QrState.Waiting(qr, progress.message.ifBlank { "等待扫码…" }))
            }
        }
    }
}

@Composable
private fun QrImage(dataUrl: String, modifier: Modifier = Modifier) {
    val bitmap = remember(dataUrl) { decodeDataUrl(dataUrl) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "登录二维码",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("二维码加载失败")
        }
    }
}

private fun decodeDataUrl(dataUrl: String): Bitmap? {
    val comma = dataUrl.indexOf(',')
    if (comma < 0) return null
    return runCatching {
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VerifyWebView(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webViewClient = WebViewClient()
            }
        },
        update = { web ->
            if (web.url != url) web.loadUrl(url)
        },
    )
}
