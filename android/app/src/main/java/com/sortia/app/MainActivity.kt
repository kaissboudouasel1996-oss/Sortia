package com.sortia.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SortiaTheme { Root(resumes) } }
    }

    // Coming back from the "All files access" screen: re-check the permission.
    override fun onResume() {
        super.onResume()
        resumes++
    }
}

@Composable
private fun Root(resumes: Int) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var opts by remember { mutableStateOf(Store.load(ctx)) }
    var grantTick by remember { mutableIntStateOf(0) }
    val hasAccess = remember(resumes, grantTick) { Perms.hasAccess(ctx) }

    val legacyPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        grantTick++
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    fun update(o: Options) {
        opts = o
        Store.save(ctx, o)
        AutoTidyScheduler.apply(ctx, o.autoTidy)
    }

    fun grant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                )
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPerms.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }

    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Perms.canNotify(ctx)) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF111A3D), P.Bg))),
    ) {
        when (screen) {
            Screen.HOME -> HomeScreen(opts, hasAccess, onGrant = { grant() }, go = { screen = it })
            Screen.FOLDERS -> FoldersScreen(opts, onChange = { update(it) }, askNotifications = { askNotifications() }) {
                screen = Screen.HOME
            }
            Screen.DUPES -> DuplicatesScreen(opts) { screen = Screen.HOME }
            Screen.HISTORY -> HistoryScreen { screen = Screen.HOME }
        }
    }
}
