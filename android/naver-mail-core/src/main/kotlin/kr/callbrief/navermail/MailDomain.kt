package kr.callbrief.navermail

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

internal fun digestBytes(value:ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it) }
internal fun digest(value:String) = digestBytes(value.toByteArray(Charsets.UTF_8))

/** An app owner and a NAVER mailbox are separate identities. Never derive either from a caller name. */
data class AccountBinding(val ownerId: String, val address: String) {
    init {
        require(ownerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "INVALID_OWNER" }
        require(address == address.lowercase(Locale.ROOT) && address.matches(Regex("[a-z0-9._-]{1,64}@naver\\.com"))) { "NAVER_PERSONAL_MAIL_ONLY" }
    }
    val key: String get() = digest("naver-imap-v1\u0000$ownerId\u0000$address")
    override fun toString() = "AccountBinding(key=$key)"
}

enum class Origin { IMAP_REAL, SYNTHETIC_FIXTURE }
enum class ConnectionState { NOT_CONNECTED, APPROVAL_REQUIRED, CONNECTING, VERIFY_REQUIRED, CONNECTED, DEVICE_UNLOCK_REQUIRED, REAUTH_REQUIRED, RETRY_WAIT, STORAGE_UNAVAILABLE, DISCONNECTED }
enum class MailError { APPROVAL_REQUIRED, AUTH_REQUIRED, IMAP_PERMISSION_OR_AUTH, TLS_REJECTED, NETWORK, RATE_LIMITED, CANCELLED, STORAGE_KEY_LOST, STORAGE_IO, INVALID_RESPONSE, UNSUPPORTED_MIME }
class MailFailure(val code: MailError) : Exception(code.name)

object MailStateText {
    fun connection(state: ConnectionState): String = when(state) {
        ConnectionState.NOT_CONNECTED -> "네이버 메일 미연결 · 읽은 실제 메일 없음"
        ConnectionState.APPROVAL_REQUIRED -> "계정 연결·실제 메일 읽기 승인 대기"
        ConnectionState.CONNECTING -> "네이버 메일 연결 확인 중"
        ConnectionState.VERIFY_REQUIRED -> "저장된 계정 · 연결 다시 확인 필요"
        ConnectionState.CONNECTED -> "네이버 메일 연결 확인됨 · 선택 범위만 읽기"
        ConnectionState.DEVICE_UNLOCK_REQUIRED -> "기기 잠금 해제 후 다시 확인해 주세요"
        ConnectionState.REAUTH_REQUIRED -> "인증·IMAP 권한 다시 확인 필요"
        ConnectionState.RETRY_WAIT -> "일시 오류 · 잠시 후 직접 재시도해 주세요"
        ConnectionState.STORAGE_UNAVAILABLE -> "저장소·암호화 키 확인 필요 · 기존 자료 보존"
        ConnectionState.DISCONNECTED -> "연결 해제됨 · 수집 중단"
    }
    fun error(code: MailError): String = when(code) {
        MailError.APPROVAL_REQUIRED -> "이 검증본에서는 새 계정·비밀번호 저장과 실제 메일 읽기를 시작할 수 없어요."
        MailError.AUTH_REQUIRED, MailError.IMAP_PERMISSION_OR_AUTH -> "네이버 2단계 인증·앱 비밀번호·IMAP 사용 설정을 직접 확인해 주세요. 비밀번호 삭제, 2단계 인증 해제 또는 90일 미사용으로 권한이 바뀌었을 수 있어요. 반복 로그인은 중단했어요."
        MailError.TLS_REJECTED -> "서버의 보안 연결을 확인할 수 없어 중단했어요."
        MailError.NETWORK, MailError.RATE_LIMITED -> "연결이 불안정하거나 서버가 요청을 제한했어요. 잠시 후 다시 시도해 주세요."
        MailError.CANCELLED -> "읽기를 중단했어요. 받은 늦은 결과는 반영하지 않아요."
        MailError.STORAGE_KEY_LOST, MailError.STORAGE_IO -> "기기에 저장한 계정 자료를 확인할 수 없어요. 새 키로 덮어쓰지 않았어요."
        MailError.INVALID_RESPONSE -> "메일 서버의 응답을 안전하게 처리할 수 없어 중단했어요."
        MailError.UNSUPPORTED_MIME -> "이 본문 형식은 아직 처리하지 않았어요. 첨부파일은 읽지 않았어요."
    }
}

/** Build configuration is supplied by the host. This handoff always installs denied(). */
class AccountActionPolicy private constructor(val approved: Boolean) {
    fun requireApproved() { if (!approved) throw MailFailure(MailError.APPROVAL_REQUIRED) }
    companion object {
        fun denied() = AccountActionPolicy(false)
        // A host can use this only after explicit user authorization and its final security review.
        fun approvedByHost() = AccountActionPolicy(true)
    }
}

/** Do not persist in Bundle, logs, JSON or string templates. Ownership of the buffer transfers here. */
class SecretChars(private val chars: CharArray) : AutoCloseable {
    private var closed = false
    fun <T> useChars(block: (CharArray) -> T): T { check(!closed); return block(chars) }
    override fun close() { chars.fill('\u0000'); closed = true }
    override fun toString() = "SecretChars(REDACTED)"
}

/** A consent receipt covers one account and one immutable, bounded scan. Date range is [since, before). */
data class MailScope(
    val account: AccountBinding, val caseId: String, val counterpartId: String,
    val correspondentAddresses: Set<String>, val keywords: Set<String>,
    val since: LocalDate, val before: LocalDate, val mailboxes: Set<String> = setOf("INBOX"),
    val limit: Int = 50, val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val relationshipConfirmedByUser: Boolean = false
) {
    init {
        require(caseId.isNotBlank() && caseId.length <= 100 && !caseId.any(Char::isISOControl) && counterpartId.isNotBlank() && counterpartId.length <= 100 && !counterpartId.any(Char::isISOControl))
        require(relationshipConfirmedByUser) { "COUNTERPART_EMAIL_CONFIRMATION_REQUIRED" }
        require(correspondentAddresses.size in 1..8 && correspondentAddresses.all { it.matches(Regex("[a-z0-9.!#%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9.-]{1,190}")) }) { "EXACT_ADDRESSES_REQUIRED" }
        require(keywords.size in 1..8 && keywords.all { it.trim().length in 2..80 && !it.any(Char::isISOControl) }) { "CASE_KEYWORDS_REQUIRED" }
        require(before > since && java.time.temporal.ChronoUnit.DAYS.between(since, before) <= 31) { "MAX_31_DAYS" }
        require(mailboxes == setOf("INBOX")) { "V1_INBOX_ONLY" }
        require(limit in 1..50 && revision > 0 && id.matches(Regex("[A-Za-z0-9-]{1,80}")))
    }
    // Immutable scope digest protects mailbox/time/filter and business mapping, not just the scope ID.
    val fingerprint: String get() = digest(listOf(account.key, id, revision, caseId, counterpartId,
        correspondentAddresses.sorted(), keywords.sorted(), since, before, mailboxes.sorted(), limit).joinToString("\u0000"))
}

data class MailRef(val accountKey: String, val mailbox: String, val uidValidity: Long, val uid: Long) {
    init { require(accountKey.matches(Regex("[0-9a-f]{64}")) && mailbox == "INBOX" && uidValidity > 0 && uid > 0) }
}
enum class BodyState { NOT_SELECTED, PLAIN_AVAILABLE, TRUNCATED, MIME_UNPROCESSED, ATTACHMENTS_UNPROCESSED }
data class HeaderEnvelope(
    val ref: MailRef, val internalDate: Instant, val messageId: String?, val from: Set<String>, val to: Set<String>,
    val subject: String, val rawHeaderSha: String, val mimeType: String,
    val disposition: String = "", val transferEncoding: String = "7bit", val charset: String = "UTF-8",
    val headerTruncated: Boolean = false, val origin: Origin = Origin.IMAP_REAL,
    val mailboxDate:LocalDate = internalDate.atZone(java.time.ZoneOffset.UTC).toLocalDate()
)
data class PlainBody(val ref: MailRef, val text: String, val truncated: Boolean, val origin: Origin)
enum class Relation { CANDIDATE, UNCERTAIN }
enum class UpdateKind { PROGRESS, REQUIRED_DOCUMENT, TODO }
data class Evidence(val ref: MailRef, val origin: Origin, val field: String, val quote: String, val start: Int, val end: Int, val contentSha: String)
data class UpdateProposal(val kind: UpdateKind, val text: String, val evidence: Evidence, val requiresUserConfirmation: Boolean = true)
data class MailCandidate(
    val header: HeaderEnvelope, val scopeFingerprint: String, val relation: Relation,
    val reasons: List<String>, val uncertainties: List<String>, val bodyState: BodyState,
    val duplicateOf: MailRef? = null, val proposals: List<UpdateProposal> = emptyList()
)
data class ScanBatch(val account: AccountBinding, val scope: MailScope, val epoch: Long, val origin: Origin,
    val candidates: List<MailCandidate>, val coverageIncomplete: Boolean, val notes: List<String>,
    val selectedBodies:Map<MailRef,PlainBody> = emptyMap(),val intakeRunTag:String="")

/** There are intentionally no send, move, delete, flag, folder-create, or attachment-download methods. */
interface ReadOnlyMailbox {
    val origin: Origin
    fun open(account: AccountBinding, secret: SecretChars): Session
    interface Session : AutoCloseable {
        fun examineInbox(): Long
        fun search(scope: MailScope): SearchResult
        fun header(ref: MailRef): HeaderEnvelope
        fun selectedPlainBody(header: HeaderEnvelope): PlainBody
    }
}
data class SearchResult(val uids: List<Long>, val hasMore: Boolean)

interface MailProposalInbox {
    /** Must be atomic and recheck account, scope fingerprint and epoch in the host persistence transaction. */
    fun replace(batch: ScanBatch)
    fun purge(accountKey: String)
}

/** Suggestions are never canonical business updates. A future host writer must check current revisions. */
data class ConfirmedMailUpdate(val accountKey: String, val scopeFingerprint: String, val caseId: String,
    val counterpartId: String, val expectedBusinessRevision: Long, val evidence: Evidence, val kind: UpdateKind, val selectedText: String)
interface MailUpdateReviewPort { fun reviewOnly(updates: List<ConfirmedMailUpdate>) }

object MailRetry {
    fun delaySeconds(error: MailError, attempt: Int): Long? = when {
        error !in setOf(MailError.NETWORK, MailError.RATE_LIMITED) || attempt !in 0..2 -> null
        else -> listOf(30L, 120L, 600L)[attempt]
    }
}
