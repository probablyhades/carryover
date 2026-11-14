package com.akfaiz.carryover

import org.json.JSONArray
import org.json.JSONObject

// The sync engine's JSON answers (see internal/agent in the Go code).

data class Remote(val name: String, val type: String)

data class Info(
    val version: String,
    val deviceName: String,
    val remote: String,
    val folder: String,
    val cloudConfigured: Boolean,
    val remotes: List<Remote>,
) {
    companion object {
        fun parse(o: JSONObject) = Info(
            version = o.optString("version"),
            deviceName = o.optString("deviceName"),
            remote = o.optString("remote"),
            folder = o.optString("folder"),
            cloudConfigured = o.optBoolean("cloudConfigured"),
            remotes = o.optJSONArray("remotes").objects().map { Remote(it.optString("name"), it.optString("type")) },
        )
    }
}

data class PathHint(val device: String, val path: String)

data class Slot(
    val key: String,
    val label: String,
    val include: List<String>,
    val path: String,
    val resolved: String,
    val error: String,
    val elsewhere: List<PathHint>,
) {
    companion object {
        fun parse(o: JSONObject) = Slot(
            key = o.optString("key"),
            label = o.optString("label"),
            include = o.optJSONArray("include").strings(),
            path = o.optString("path"),
            resolved = o.optString("resolved"),
            error = o.optString("error"),
            elsewhere = o.optJSONArray("elsewhere").objects().map { PathHint(it.optString("device"), it.optString("path")) },
        )
    }
}

data class Status(
    val lastSyncAt: String,
    val lastAction: String,
    val conflict: Boolean,
    val lastError: String,
) {
    companion object {
        fun parse(o: JSONObject?) = Status(
            lastSyncAt = o?.optString("lastSyncAt").orEmpty(),
            lastAction = o?.optString("lastAction").orEmpty(),
            conflict = o?.optBoolean("conflict") ?: false,
            lastError = o?.optString("lastError").orEmpty(),
        )
    }
}

data class Profile(
    val key: String,
    val emulatorId: String,
    val emulatorName: String,
    val slots: List<Slot>,
    val ready: Boolean,
    val setupError: String,
    val status: Status,
) {
    companion object {
        fun parse(o: JSONObject) = Profile(
            key = o.optString("key"),
            emulatorId = o.optString("emulatorId"),
            emulatorName = o.optString("emulatorName"),
            slots = o.optJSONArray("slots").objects().map(Slot::parse),
            ready = o.optBoolean("ready"),
            setupError = o.optString("setupError"),
            status = Status.parse(o.optJSONObject("status")),
        )
    }
}

data class Game(val id: String, val name: String, val profiles: List<Profile>) {
    companion object {
        fun parse(o: JSONObject) = Game(
            id = o.optString("id"),
            name = o.optString("name"),
            profiles = o.optJSONArray("profiles").objects().map(Profile::parse),
        )
    }
}

data class SyncResult(
    val key: String,
    val name: String,
    /** push, pull, none, conflict, skipped or error */
    val action: String,
    val summary: String,
    val reason: String,
    val error: String,
) {
    companion object {
        fun parse(o: JSONObject) = SyncResult(
            key = o.optString("key"),
            name = o.optString("name"),
            action = o.optString("action"),
            summary = o.optString("summary"),
            reason = o.optString("reason"),
            error = o.optString("error"),
        )

        fun parseAll(o: JSONObject) = o.optJSONArray("results").objects().map(::parse)
    }
}

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
