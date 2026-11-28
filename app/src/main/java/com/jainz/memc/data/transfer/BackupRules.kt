package com.jainz.memc.data.transfer

import com.jainz.memc.MemcApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One automatic backup: everything in a phone folder (picked with Android's folder picker)
 * goes to a folder on a network share. Files already there with the same size are skipped.
 */
data class BackupRule(
    val id: String = UUID.randomUUID().toString(),
    /** Android's address for the phone folder (kept readable across restarts). */
    val treeUri: String,
    /** The phone folder's name, for display. */
    val label: String,
    val sourceId: Long,
    /** Folder inside the share, forward slashes, no leading slash. */
    val folder: String,
    /** Remove each file from the phone once it is safely on the share. */
    val deleteAfter: Boolean = false,
    /** Only video files (otherwise everything in the folder). */
    val videosOnly: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val enabled: Boolean = true,
    val lastRunAt: Long = 0,
    val lastResult: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("treeUri", treeUri).put("label", label)
        .put("sourceId", sourceId).put("folder", folder)
        .put("deleteAfter", deleteAfter).put("videosOnly", videosOnly)
        .put("wifiOnly", wifiOnly).put("chargingOnly", chargingOnly).put("enabled", enabled)
        .put("lastRunAt", lastRunAt).put("lastResult", lastResult)

    companion object {
        fun fromJson(o: JSONObject) = BackupRule(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            treeUri = o.optString("treeUri"),
            label = o.optString("label"),
            sourceId = o.optLong("sourceId"),
            folder = o.optString("folder"),
            deleteAfter = o.optBoolean("deleteAfter", false),
            videosOnly = o.optBoolean("videosOnly", true),
            wifiOnly = o.optBoolean("wifiOnly", true),
            chargingOnly = o.optBoolean("chargingOnly", false),
            enabled = o.optBoolean("enabled", true),
            lastRunAt = o.optLong("lastRunAt", 0),
            lastResult = o.optString("lastResult"),
        )
    }
}

object BackupRules {
    private val pref get() = MemcApp.graph.settings.backupRules

    fun parse(json: String): List<BackupRule> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { BackupRule.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun all(): List<BackupRule> = parse(pref.value)

    @Synchronized
    fun save(rules: List<BackupRule>) {
        pref.set(JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString())
    }

    @Synchronized
    fun upsert(rule: BackupRule) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == rule.id }
        if (i >= 0) list[i] = rule else list += rule
        save(list)
    }

    @Synchronized
    fun remove(id: String) = save(all().filterNot { it.id == id })

    /** Records how the last run of a rule went (re-reads so concurrent edits aren't lost). */
    @Synchronized
    fun report(id: String, result: String) {
        save(all().map { if (it.id == id) it.copy(lastRunAt = System.currentTimeMillis(), lastResult = result) else it })
    }
}
