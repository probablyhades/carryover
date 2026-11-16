package com.akfaiz.carryover

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Storage access: the engine reads save folders by path, so it needs "All files access". */
object Storage {
    fun hasAccess(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** The settings page that grants "All files access" (Android 11+). */
    @RequiresApi(Build.VERSION_CODES.R)
    fun accessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${ctx.packageName}".toUri())

    /** Android 10 and older ask for the storage permission instead. */
    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    @Suppress("BatteryLife") // a sync app that must run in the background
    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri())

    /**
     * Turns a folder picked with the system file picker into a file path:
     * "primary:RetroArch/saves" is /storage/emulated/0/RetroArch/saves, and
     * "1234-ABCD:saves" a folder on that SD card. Returns null for folders
     * that have no path (cloud providers, Downloads shortcuts, ...).
     */
    fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val volume = id.substringBefore(':')
        val rest = id.substringAfter(':', "")
        @Suppress("DEPRECATION")
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (rest.isEmpty()) root else "$root/$rest"
    }

    /** Folders inside Android/data and Android/obb belong to one app; Carryover can't read them. */
    fun isPrivateAppFolder(path: String): Boolean =
        Regex("""/Android/(data|obb)(/|$)""").containsMatchIn(path)

    /** Common save folders of Android emulators, as hints for an emulator by name. */
    fun suggestions(emulatorName: String): List<String> {
        val n = emulatorName.lowercase()
        return buildList {
            if ("retroarch" in n) add("{storage}/RetroArch/saves")
            if ("ppsspp" in n || "psp" in n) add("{storage}/PSP/SAVEDATA")
        }
    }
}
