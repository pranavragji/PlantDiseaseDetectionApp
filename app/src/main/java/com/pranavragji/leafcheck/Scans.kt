package com.pranavragji.leafcheck

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// One saved check. `crop` is the plant the user picked, or "" for auto-detect.
data class Scan(val time: Long, val label: String, val confidence: Float, val photo: String, val crop: String) {
    val healthy get() = conditionOf(label) == "healthy"
}

// Scan history for the dashboard, kept as a small JSON file in app storage. Newest first.
object Scans {
    private fun file(context: Context) = File(context.filesDir, "scans.json")

    fun all(context: Context): List<Scan> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val a = runCatching { JSONArray(f.readText()) }.getOrElse { return emptyList() }
        return List(a.length()) { i ->
            a.getJSONObject(i).run {
                Scan(getLong("time"), getString("label"), getDouble("confidence").toFloat(), getString("photo"), optString("crop"))
            }
        }
    }

    fun find(context: Context, time: Long) = all(context).firstOrNull { it.time == time }

    fun add(context: Context, scan: Scan) = save(context, listOf(scan) + all(context).filter { it.time != scan.time })

    fun clear(context: Context) {
        file(context).delete()
        File(context.filesDir, "scans").deleteRecursively()
    }

    private fun save(context: Context, scans: List<Scan>) {
        val a = JSONArray()
        for (s in scans) a.put(
            JSONObject().put("time", s.time).put("label", s.label).put("confidence", s.confidence.toDouble())
                .put("photo", s.photo).put("crop", s.crop)
        )
        // write then rename, so a crash mid-write never wipes the history
        val tmp = File(context.filesDir, "scans.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(context))
    }
}
