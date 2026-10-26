package kr.callbrief.core

import java.net.URI
import java.time.*
import java.util.Locale

object SeoulTime {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    fun yesterday(now: Instant): LocalDate = now.atZone(zone).toLocalDate().minusDays(1)
    fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()
    fun nextReport(now: Instant): Instant {
        val local = now.atZone(zone)
        val today = local.toLocalDate().atTime(6, 0).atZone(zone)
        return (if (today.toInstant().isAfter(now)) today else today.plusDays(1)).toInstant()
    }
    fun eligible(startedAt: Instant, firstAllowedDate: LocalDate, now: Instant): Boolean =
        !startedAt.isBefore(startOfDay(firstAllowedDate)) && !startedAt.isAfter(now.minusSeconds(60))
}

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

object ServiceEndpoint {
    fun normalize(input: String): String {
        require(input.length <= 2048) { "서비스 주소가 너무 길어요." }
        val u = URI(input.trim())
        require(u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null &&
            u.rawQuery == null && u.rawFragment == null && (u.port == -1 || u.port in 1..65535)) {
            "사용자 정보·쿼리·조각이 없는 HTTPS 서비스 주소를 입력해 주세요."
        }
        require(u.path.orEmpty().split('/').none { it == ".." || it == "." }) { "단순한 서비스 주소를 입력해 주세요." }
        return u.toASCIIString().trimEnd('/')
    }
    fun kakaoAuthorization(endpoint:String, returned:String):String {
        val saved=URI(normalize(endpoint));val url=URI(returned)
        fun port(value:URI)=if(value.port==-1) 443 else value.port
        require(url.scheme=="https" && url.host?.lowercase(Locale.ROOT)==saved.host.lowercase(Locale.ROOT) &&
            port(url)==port(saved) && url.userInfo==null && url.fragment==null &&
            url.path==saved.path.orEmpty().trimEnd('/')+"/v1/kakao/authorize" &&
            Regex("state=[A-Za-z0-9_-]{24,256}").matches(url.rawQuery.orEmpty())) {
            "서버가 반환한 본인 로그인 주소를 확인할 수 없어요."
        }
        return url.toASCIIString()
    }
}

enum class TaskStatus(val wire: String, val label: String) {
    OPEN("open", "해야 할 일"), IN_PROGRESS("in_progress", "진행 중"), WAITING("waiting", "상대방 답변 대기"),
    NEEDS_CONFIRMATION("needs_confirmation", "확인 필요"), DONE("done", "완료");
    companion object { fun fromWire(value: String): TaskStatus = entries.firstOrNull { it.wire == value } ?: NEEDS_CONFIRMATION }
}
