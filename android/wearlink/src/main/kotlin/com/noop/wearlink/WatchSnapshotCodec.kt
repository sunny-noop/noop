package com.noop.wearlink

/**
 * Hand-rolled flat-JSON codec for [WatchSnapshot] — no org.json/kotlinx dependency, so the
 * module stays pure-JVM. The schema is a fixed flat object we control on both ends, so
 * per-key extraction is robust. decodeOrNull returns null on anything that isn't an object.
 */
object WatchSnapshotCodec {

    fun encode(s: WatchSnapshot): String = buildString {
        append('{')
        append("\"hr\":").append(s.hr?.toString() ?: "null").append(',')
        append("\"connected\":").append(s.connected).append(',')
        append("\"bonded\":").append(s.bonded).append(',')
        append("\"batteryPct\":").append(s.batteryPct?.toString() ?: "null").append(',')
        append("\"worn\":").append(s.worn).append(',')
        append("\"backfilling\":").append(s.backfilling).append(',')
        append("\"lastSyncAt\":").append(s.lastSyncAt?.toString() ?: "null").append(',')
        append("\"emittedAt\":").append(s.emittedAt)
        append('}')
    }

    fun decodeOrNull(json: String): WatchSnapshot? {
        if (!json.trimStart().startsWith("{")) return null
        return try {
            WatchSnapshot(
                hr = num(json, "hr")?.toInt(),
                connected = bool(json, "connected"),
                bonded = bool(json, "bonded"),
                batteryPct = num(json, "batteryPct")?.toDouble(),
                worn = bool(json, "worn", default = true),
                backfilling = bool(json, "backfilling"),
                lastSyncAt = num(json, "lastSyncAt")?.toLong(),
                emittedAt = num(json, "emittedAt")?.toLong() ?: 0L,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the numeric token for [key] as a string, or null if the value is JSON null/absent. */
    private fun num(json: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*(null|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)").find(json) ?: return null
        val v = m.groupValues[1]
        return if (v == "null") null else v
    }

    private fun bool(json: String, key: String, default: Boolean = false): Boolean {
        val m = Regex("\"$key\"\\s*:\\s*(true|false)").find(json) ?: return default
        return m.groupValues[1] == "true"
    }
}
