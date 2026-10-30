package kr.callbrief.core

import java.time.*
import java.util.Locale

object SeoulTime { val zone: ZoneId = ZoneId.of("Asia/Seoul") }

object RecordingNames {
    // Samsung/recorder filenames vary. Explicit calendar timestamp only; NEVER modified-time fallback.
    private val patterns = listOf(
        Regex("(?<![0-9])([0-9]{4})[-.]([0-9]{2})[-.]([0-9]{2})[T_ .-]([0-9]{2})[-.:]([0-9]{2})[-.:]([0-9]{2})(?![0-9])"),
        Regex("(?<![0-9])([0-9]{8})[_ T.-]([0-9]{6})(?![0-9])"),
        Regex("(?<![0-9])([0-9]{6})[_ T.-]([0-9]{6})(?![0-9])")
    )
    fun callerAlias(name: String): String? {
        if (startedAt(name)==null) return null
        val start=patterns.mapNotNull { it.find(name)?.range?.first }.minOrNull() ?: return null
        val prefix=PrivacyRules.removePhoneTokens(name.substring(0,start))
            .replace(Regex("^(?:통화[ _]*녹음|녹음|call(?:[ _]+recording)?)[ _.-]*",RegexOption.IGNORE_CASE),"")
            .trim().removeSuffix("_").trim()
        return prefix.takeIf { PrivacyRules.normalizeAlias(it).isNotBlank() }
    }
    fun startedAt(name: String): Instant? {
        for ((index, regex) in patterns.withIndex()) {
            val match = regex.find(name) ?: continue
            try {
                val g = match.groupValues
                val date: String
                val time: String
                if (index == 0) { date = g[1] + g[2] + g[3]; time = g[4] + g[5] + g[6] }
                else { date = if (index == 2) "20" + g[1] else g[1]; time = g[2] }
                val year = date.substring(0, 4).toInt()
                if (year !in 2000..2099) continue
                return LocalDateTime.of(year, date.substring(4,6).toInt(), date.substring(6,8).toInt(),
                    time.substring(0,2).toInt(), time.substring(2,4).toInt(), time.substring(4,6).toInt())
                    .atZone(SeoulTime.zone).toInstant()
            } catch (_: DateTimeException) { /* Continue to other explicit patterns. */ }
        }
        return null
    }
}

object AudioPolicy {
    const val MAX_BYTES: Long = 25_000_000
    private val types = mapOf("m4a" to "audio/mp4", "mp4" to "audio/mp4", "mp3" to "audio/mpeg",
        "mpeg" to "audio/mpeg", "mpga" to "audio/mpeg", "wav" to "audio/wav", "webm" to "audio/webm", "ogg" to "audio/ogg", "flac" to "audio/flac")
    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun mime(name: String): String? = types[extension(name)]
    fun safeSize(size: Long): Boolean = size in 1..MAX_BYTES
}
