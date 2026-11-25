package org.sodahub.sodam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Android 13+ 需要用户授权才能显示媒体通知（后台播放的控制入口）。
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // 登录设备身份要持久化、跨二维码复用，否则容易被护照服务当新设备限流。
        val deviceFile = File(filesDir, "qr-device.json").absolutePath
        // 凭据落在应用私有目录；AndroidManifest 已关闭系统备份。
        val cookieFile = File(filesDir, "session.cookie").absolutePath
        val audioCacheDir = File(cacheDir, "audio").absolutePath
        val qualityFile = File(filesDir, "quality.txt").absolutePath
        val cacheLimitFile = File(filesDir, "cache_limit.txt").absolutePath
        val pagesFile = File(filesDir, "pages.txt").absolutePath
        val themeFile = File(filesDir, "theme.txt").absolutePath
        val themeColorFile = File(filesDir, "theme_color.txt").absolutePath
        val themeStyleFile = File(filesDir, "theme_style.txt").absolutePath
        val uiStyleFile = File(filesDir, "ui_style.txt").absolutePath
        val guideFile = File(filesDir, "swipe_guide_seen").absolutePath
        val guideDebugFile = File(filesDir, "swipe_guide_debug").absolutePath
        val likedFile = File(filesDir, "liked_tracks.json").absolutePath
        val homeQueueFile = File(filesDir, "home_queue.json").absolutePath
        val playModeFile = File(filesDir, "play_mode.txt").absolutePath
        val sceneFile = File(filesDir, "scene_mode.txt").absolutePath
        val downloadDirFile = File(filesDir, "download_dir.txt").absolutePath
        val hapticFile = File(filesDir, "haptic_level.txt").absolutePath
        val backdropAlphaFile = File(filesDir, "backdrop_alpha.txt").absolutePath
        val playButtonStyleFile = File(filesDir, "play_button_style.txt").absolutePath
        setContent {
            SodamApp(
                deviceFile = deviceFile,
                cookieFile = cookieFile,
                cacheDir = audioCacheDir,
                qualityFile = qualityFile,
                cacheLimitFile = cacheLimitFile,
                pagesFile = pagesFile,
                themeFile = themeFile,
                themeColorFile = themeColorFile,
                themeStyleFile = themeStyleFile,
                uiStyleFile = uiStyleFile,
                guideFile = guideFile,
                guideDebugFile = guideDebugFile,
                likedFile = likedFile,
                homeQueueFile = homeQueueFile,
                playModeFile = playModeFile,
                sceneFile = sceneFile,
                downloadDirFile = downloadDirFile,
                hapticFile = hapticFile,
                backdropAlphaFile = backdropAlphaFile,
                playButtonStyleFile = playButtonStyleFile,
            )
        }
    }
}
