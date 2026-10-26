package kr.callbrief.context

import java.security.MessageDigest
import java.time.Instant

enum class Channel { CALL_ANALYSIS, SMS, MAIL }
enum class Operation { UPSERT, TOMBSTONE }
enum class Kind { APPOINTMENT, DOCUMENT, PROGRESS, TASK }
enum class Field { TITLE, STATUS, DUE, EDITION, ASSIGNEE }
enum class Certainty { CLEAR, ESTIMATED, NEEDS_REVIEW }
enum class ClaimOperation { ASSERT, CANCEL }

/** Identity is scoped to owner/channel/namespace/id, never a content hash or a name. */
data class SourceId(val owner: String, val channel: Channel, val namespace: String, val externalId: String)
data class VersionKey(val source: SourceId, val version: Long)
data class SourceRef(val key: VersionKey, val sha256: String)
data class ClaimRef(val source: SourceRef, val claimId: String)
data class Evidence(val source: SourceRef, val start: Int, val end: Int, val quote: String)

/** Local normalized DTO only. Callers supply extracted facts, not raw-text inference rules.
 * No identity assignment, receipt validation or production authorization is performed here. */
data class Claim(
    val id: String,
    val kind: Kind,
    val targetId: String,
    val values: Map<Field, String?>,
    val evidence: Evidence,
    val certainty: Certainty = Certainty.CLEAR,
    val operation: ClaimOperation = ClaimOperation.ASSERT,
    val supersedes: ClaimRef? = null,
    val origin: ClaimRef? = null,
)

data class NormalizedEvent(
    val ref: SourceRef,
    val body: String,
    val occurredAt: Instant?,
    val observedAt: Instant,
    val senderRaw: String,
    val localBindingRef: String,
    val operation: Operation = Operation.UPSERT,
    val claims: List<Claim> = emptyList(),
)

/** A relation supplied by the repository, after its own identity/work review.
 * Endpoint/name equality is deliberately not an input to the matching algorithm.
 * These records are candidate context metadata, never grants to write canonical tables. */
data class ReviewedLink(
    val source: SourceRef,
    val scope: WorkScope,
    val relationRevision: Long,
    val personEvidence: Evidence,
    val workEvidence: Evidence,
    val reviewReceiptRef: String,
)
data class WorkScope(val owner: String, val personId: String, val workId: String, val companyId: String?)
data class ItemKey(val scope: WorkScope, val kind: Kind, val targetId: String)
data class CanonicalItem(
    val revision: Long,
    val values: Map<Field, String?>,
    val manuallyEdited: Boolean = false,
    val locked: Boolean = false,
)
data class Fences(
    val user: Long, val lock: Long, val task: Long, val board: Long,
    val relation: Long, val archive: Long, val leaseEpoch: Long,
)
data class AdmittedSource(val ref: SourceRef, val localBindingRef: String, val leaseEpoch: Long)

/** Port-issued snapshot, separate from event/model fields. The adapter compares the entire
 * snapshot again inside the repository transaction, including source permissions and leases. */
data class RepositoryView(
    val owner: String,
    val fences: Fences,
    val admitted: Set<AdmittedSource>,
    val links: List<ReviewedLink>,
    val items: Map<ItemKey, CanonicalItem> = emptyMap(),
    val archived: Boolean = false,
    val hidden: Boolean = false,
)

enum class HoldReason {
    INVALID_EVENT, OWNER_MISMATCH, SOURCE_UNAVAILABLE, REVISION_CONFLICT,
    SOURCE_COLLISION, LINK_UNRESOLVED, LINK_AMBIGUOUS, LINK_EVIDENCE_STALE,
    UNCERTAIN_CLAIM, UNKNOWN_TIME, CAUSAL_TARGET_PENDING, CAUSAL_SCOPE_MISMATCH,
    CAUSAL_CYCLE, ORIGIN_MISMATCH, FACT_CONFLICT, MANUAL_DONE, MANUAL_EDIT,
    LOCKED, ARCHIVED_OR_HIDDEN,
}
data class Hold(val reason: HoldReason, val source: SourceRef? = null, val claim: ClaimRef? = null,
                val item: ItemKey? = null, val field: Field? = null)
data class FactValue(val value: String?, val provenance: Set<ClaimRef>, val origins: Set<ClaimRef>)
data class CandidateItem(val fields: Map<Field, FactValue>, val expectedItemRevision: Long?)

/** A persistable replacement projection, not a patch to apply to canonical user values.
 * Fields absent after invalidation must be removed from the candidate cache. */
data class CandidateProjection(
    val items: Map<ItemKey, CandidateItem> = emptyMap(),
    val holds: Set<Hold> = emptySet(),
    val activeSources: Set<SourceRef> = emptySet(),
) {
    val canonicalWriteAllowed: Boolean get() = false
}

enum class ReceiptStatus { ACCEPTED, HISTORICAL, DUPLICATE, COLLISION, REJECTED }
data class IngestReceipt(val id: String, val source: SourceRef, val status: ReceiptStatus,
                         val reason: HoldReason? = null)
data class ContextState(
    val owner: String,
    val revision: Long = 0,
    val events: Map<VersionKey, NormalizedEvent> = emptyMap(),
    val collisions: Map<VersionKey, Set<NormalizedEvent>> = emptyMap(),
    val receipts: Map<VersionKey, IngestReceipt> = emptyMap(),
    val projection: CandidateProjection = CandidateProjection(),
)
data class Reconciliation(val state: ContextState, val receipt: IngestReceipt)

object EventAdapter {
    /** Preserves exact UTF-8 text and caller-provided source identity. No alias normalization. */
    fun normalize(source: SourceId, version: Long, body: String, occurredAt: Instant?,
                  observedAt: Instant, senderRaw: String, localBindingRef: String,
                  operation: Operation = Operation.UPSERT,
                  claims: (SourceRef) -> List<Claim> = { emptyList() }): NormalizedEvent {
        val ref = SourceRef(VersionKey(source, version), sha256(body))
        return NormalizedEvent(ref, body, occurredAt, observedAt, senderRaw, localBindingRef, operation, claims(ref))
    }

    /** Exact code-point offsets, including supplementary characters; end is exclusive. */
    fun evidence(ref: SourceRef, body: String, start: Int, end: Int): Evidence {
        require(start >= 0 && end > start && end <= body.codePointCount(0, body.length))
        return Evidence(ref, start, end, body.substring(body.offsetByCodePoints(0, start), body.offsetByCodePoints(0, end)))
    }
}

fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
