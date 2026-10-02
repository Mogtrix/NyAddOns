package dev.nytrix.nyaddons.core

object TimeUtils {

    private val unit = Regex("(\\d+)\\s*([dhms])")

    /** Formats like SkyHanni: `1h 23m 4s`, leaving out units that are zero. */
    fun format(millis: Long): String {
        if (millis <= 0) return "Soon"
        var seconds = (millis + 999) / 1000
        val days = seconds / 86400
        seconds %= 86400
        val hours = seconds / 3600
        seconds %= 3600
        val minutes = seconds / 60
        seconds %= 60
        return buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0) add("${seconds}s")
        }.joinToString(" ")
    }

    /** Colour for a countdown: red under 1 minute, gold under 3, yellow under 10. */
    fun timerColor(millis: Long): String = when {
        millis < 60_000 -> "§c"
        millis < 180_000 -> "§6"
        millis < 600_000 -> "§e"
        else -> "§f"
    }

    /** Parses `59m 30s`, `59m30s`, `1h 2m` and so on. Returns null if no unit is found. */
    fun parse(text: String): Long? {
        var total = 0L
        var found = false
        for (match in unit.findAll(text)) {
            val amount = match.groupValues[1].toLongOrNull() ?: continue
            total += amount * when (match.groupValues[2]) {
                "d" -> 86_400_000L
                "h" -> 3_600_000L
                "m" -> 60_000L
                else -> 1_000L
            }
            found = true
        }
        return if (found) total else null
    }
}
