package kr.callbrief

import kr.callbrief.core.AudioPolicy
import kr.callbrief.core.SeoulTime
import kr.callbrief.core.ServiceEndpoint
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ApiException(val status: Int, val issue: String) : IOException("서비스 오류 $status ($issue)")

/** Fixed HTTPS origin, system trust, and NO redirects. Credentials never enter URLs or logs. */
class ServiceApi(val endpoint: String, private val token: String) {
    init {
        require(NativeAnalysisAvailability.canTransfer()) { NativeAnalysisAvailability.MESSAGE }
        ServiceEndpoint.normalize(endpoint); require(token.length >= 32) { "설치용 토큰을 확인해 주세요." }
    }
    private fun connection(path: String, method: String): HttpURLConnection {
        require(path.startsWith("/v1/") && !path.contains(".."))
        return (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; instanceFollowRedirects = false
            connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
    }
    private fun response(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        // API error bodies are parsed only for safe machine codes; never displayed verbatim.
        val text = stream?.use { input ->
            val buffer = java.io.ByteArrayOutputStream(); val bytes = ByteArray(8192)
            while (true) { val n = input.read(bytes); if (n < 0) break; if (buffer.size() + n > 2_000_000) throw IOException("서비스 응답이 너무 커요."); buffer.write(bytes, 0, n) }
            buffer.toString("UTF-8")
        } ?: "{}"
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (code !in 200..299) {
            val detail = json.opt("detail")
            val issue = when (detail) { is JSONObject -> detail.optString("code", "request_failed"); is String -> detail; else -> "request_failed" }
            throw ApiException(code, issue.takeIf { Regex("[a-z_]{1,60}").matches(it) } ?: "request_failed")
        }
        return json
    }
    fun get(path: String): JSONObject { val c = connection(path, "GET"); return try { response(c) } finally { c.disconnect() } }
    fun json(path: String, method: String, body: JSONObject): JSONObject {
        val c = connection(path, method)
        return try {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toString().toByteArray(Charsets.UTF_8); c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }; response(c)
        } finally { c.disconnect() }
    }
    fun preflight(recording: RecordingRef, identity: RecordingIdentity): JSONObject = json("/v1/recording-preflight", "POST",
        JSONObject().put("started_at", recording.startedAt!!.atZone(SeoulTime.zone).toOffsetDateTime().toString())
            .put("counterpart_phone", identity.phone).put("counterpart_alias", identity.alias.ifBlank { null } ?: JSONObject.NULL).put("identity_verified", true))
    fun upload(context: android.content.Context, recording: RecordingRef, fingerprint: String, caseId: String?, identity: RecordingIdentity, ticket: String?, contentSha256: String): JSONObject {
        val initial=SecureSettings(context).snapshot()
        require(initial.optBoolean("cloudEnabled") && !initial.optBoolean("pendingRevocation")) { "전송 동의가 중지돼 음성을 읽거나 보내지 않았어요." }
        require(LocalPrivacy.requireAllowed(recording, initial)==identity) { "상대방 확인 정보가 바뀌어 전송을 중단했어요." }
        val c = connection("/v1/recordings", "POST")
        return try {
            c.doOutput = true; c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("Idempotency-Key", fingerprint)
            c.setRequestProperty("X-Expected-Audio-SHA256", contentSha256)
            if (!ticket.isNullOrBlank()) {
                c.setRequestProperty("X-Upload-Ticket", ticket)
                c.setRequestProperty("X-Counterpart-Phone", identity.phone)
                c.setRequestProperty("X-Identity-Verified", "true")
                if (identity.alias.isNotBlank()) c.setRequestProperty("X-Counterpart-Alias-Base64", android.util.Base64.encodeToString(identity.alias.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
            }
            val boundary = "CallBrief" + UUID.randomUUID().toString().replace("-", "")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                fun text(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
                fun field(name: String, value: String) {
                    text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                field("started_at", recording.startedAt!!.atZone(SeoulTime.zone).toOffsetDateTime().toString())
                if (!caseId.isNullOrBlank()) field("case_id", caseId)
                else {
                    field("counterpart_key", SecureSettings(context).counterpartKey(identity.phone))
                    field("counterpart", identity.alias.ifBlank { "상대방 · 끝자리 " + identity.phone.takeLast(4) })
                    field("topic", "미분류")
                }
                // The provider sees an opaque filename, not names/phone numbers from the actual filename.
                text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fingerprint.${AudioPolicy.extension(recording.name)}\"\r\nContent-Type: ${recording.mime}\r\n\r\n")
                context.contentResolver.openInputStream(recording.uri)?.use { input ->
                    val bytes = ByteArray(64 * 1024); var total = 0L
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    while (true) {
                        val state = SecureSettings(context).snapshot()
                        if (!state.optBoolean("cloudEnabled") || state.optBoolean("pendingRevocation")) throw IOException("전송이 중지됐어요.")
                        require(LocalPrivacy.requireAllowed(recording, state)==identity) { "상대방 확인 정보가 바뀌어 전송을 중단했어요." }
                        val n = input.read(bytes); if (n < 0) break; total += n
                        if (total > AudioPolicy.MAX_BYTES || total > recording.size) throw IOException("파일 크기가 바뀌었어요. 다시 확인해 주세요.")
                        digest.update(bytes,0,n); out.write(bytes, 0, n)
                    }
                    if (total != recording.size) throw IOException("파일이 아직 저장 중이거나 바뀌었어요.")
                    if (digest.digest().joinToString("") { "%02x".format(it) } != contentSha256) throw IOException("전송 도중 파일 내용이 바뀌어 중단했어요.")
                } ?: throw IOException("녹음 파일을 읽을 수 없어요.")
                text("\r\n--$boundary--\r\n")
            }
            response(c)
        } finally { c.disconnect() }
    }
    companion object {
        fun from(settings: JSONObject): ServiceApi {
            require(NativeAnalysisAvailability.canTransfer()) { NativeAnalysisAvailability.MESSAGE }
            return ServiceApi(settings.getString("endpoint"), settings.getString("token"))
        }
    }
}
