package com.akfaiz.carryover

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** The profile to show (a sync key), e.g. from a conflict notification. */
    private val openProfile = mutableStateOf<String?>(null)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openProfile.value = intent.getStringExtra(EXTRA_PROFILE)
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.canNotify(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            CarryoverTheme {
                CarryoverApp(vm, openProfile)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PROFILE)?.let {
            openProfile.value = it
            vm.refresh(offline = true)
        }
    }

    override fun onResume() {
        super.onResume()
        // The watcher can only be started while the app is in the foreground.
        WatchService.apply(this)
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}

private val Teal = Color(0xFF14B8A6)

@Composable
fun CarryoverTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Teal)
        else -> lightColorScheme(primary = Color(0xFF0F766E))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
