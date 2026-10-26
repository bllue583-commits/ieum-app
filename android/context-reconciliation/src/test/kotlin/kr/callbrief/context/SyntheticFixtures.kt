package kr.callbrief.context

import java.time.Instant

/** Hand-authored synthetic inputs. No call/SMS/mail/contact collection or model execution. */
internal object SyntheticFixtures {
    const val OWNER = "owner-synthetic-a"
    val scope = WorkScope(OWNER, "person-synthetic-a", "work-synthetic-1", "company-synthetic")
    val fences = Fences(1, 1, 1, 1, 1, 1, 1)

    fun event(id: String = "call-1", channel: Channel = Channel.CALL_ANALYSIS, version: Long = 1,
              at: String? = "2026-10-01T00:00:00Z", body: String = "합성 담당 가의 업무 W-1 견적서를 보내 주세요.",
              target: String = "quote-v1", kind: Kind = Kind.DOCUMENT,
              values: Map<Field, String?> = mapOf(Field.STATUS to "REQUIRED", Field.DUE to "2026-10-05"),
              operation: Operation = Operation.UPSERT): NormalizedEvent = EventAdapter.normalize(
        SourceId(OWNER, channel, "synthetic-only", id), version, body, at?.let(Instant::parse),
        Instant.parse("2026-10-10T00:00:00Z"), if (channel == Channel.MAIL) "contact@example.invalid" else "synthetic-endpoint",
        "synthetic-local-binding:$id", operation) { ref ->
        if (operation == Operation.TOMBSTONE) emptyList()
        else listOf(Claim("claim-1", kind, target, values, EventAdapter.evidence(ref, body, 0, body.codePointCount(0, body.length))))
    }

    fun claim(event: NormalizedEvent, id: String, kind: Kind, target: String, values: Map<Field, String?>,
              supersedes: ClaimRef? = null) = Claim(id, kind, target, values,
        EventAdapter.evidence(event.ref, event.body, 0, event.body.codePointCount(0, event.body.length)), supersedes = supersedes)

    fun link(event: NormalizedEvent, workScope: WorkScope = scope) = ReviewedLink(event.ref, workScope, fences.relation,
        event.claims.first().evidence, event.claims.first().evidence, "synthetic-review:${event.ref.key.source.externalId}")

    fun view(events: List<NormalizedEvent>, links: List<ReviewedLink> = events.filter { it.operation == Operation.UPSERT }.map(::link)) =
        RepositoryView(OWNER, fences, events.map { AdmittedSource(it.ref, it.localBindingRef, fences.leaseEpoch) }.toSet(), links)

    fun chain(): List<NormalizedEvent> {
        val call = event(body = "[합성] 담당 가, W-1. 10월 5일 약속, 견적서 1판 제출, 준비 시작.").let { e -> e.copy(claims = listOf(
            claim(e, "document-v1", Kind.DOCUMENT, "quote-v1", mapOf(Field.TITLE to "견적서", Field.EDITION to "1", Field.STATUS to "REQUIRED", Field.DUE to "2026-10-05")),
            claim(e, "appointment", Kind.APPOINTMENT, "visit", mapOf(Field.TITLE to "자료 검토", Field.STATUS to "OPEN", Field.DUE to "2026-10-05T10:00:00+09:00")),
            claim(e, "task", Kind.TASK, "submit", mapOf(Field.TITLE to "견적서 제출", Field.STATUS to "OPEN", Field.DUE to "2026-10-05")),
            claim(e, "progress", Kind.PROGRESS, "work", mapOf(Field.STATUS to "IN_PROGRESS")),
        )) }
        val sms = event("sms-1", Channel.SMS, at = "2026-10-02T01:00:00Z",
            body = "[합성] W-1 견적서 1판 수령. 2판을 10월 7일까지 주세요.").let { e -> e.copy(claims = listOf(
            claim(e, "received-v1", Kind.DOCUMENT, "quote-v1", mapOf(Field.STATUS to "RECEIVED"), ClaimRef(call.ref, "document-v1")),
            claim(e, "required-v2", Kind.DOCUMENT, "quote-v2", mapOf(Field.TITLE to "견적서", Field.EDITION to "2", Field.STATUS to "REQUIRED", Field.DUE to "2026-10-07")),
            claim(e, "task-change", Kind.TASK, "submit", mapOf(Field.DUE to "2026-10-07"), ClaimRef(call.ref, "task")),
        )) }
        val mail = event("mail-1", Channel.MAIL, at = "2026-10-03T02:00:00Z",
            body = "[합성] W-1: 견적서 2판과 사업자등록증 10월 9일까지. 약속도 9일로 변경, 회신 대기.").let { e -> e.copy(claims = listOf(
            claim(e, "due-change", Kind.DOCUMENT, "quote-v2", mapOf(Field.DUE to "2026-10-09"), ClaimRef(sms.ref, "required-v2")),
            claim(e, "registration", Kind.DOCUMENT, "registration", mapOf(Field.TITLE to "사업자등록증", Field.STATUS to "REQUIRED", Field.DUE to "2026-10-09")),
            claim(e, "appointment-change", Kind.APPOINTMENT, "visit", mapOf(Field.DUE to "2026-10-09T10:00:00+09:00"), ClaimRef(call.ref, "appointment")),
            claim(e, "task-change", Kind.TASK, "submit", mapOf(Field.DUE to "2026-10-09"), ClaimRef(sms.ref, "task-change")),
            claim(e, "waiting", Kind.PROGRESS, "work", mapOf(Field.STATUS to "WAITING"), ClaimRef(call.ref, "progress")),
        )) }
        return listOf(call, sms, mail)
    }

    fun reduce(events: List<NormalizedEvent>, view: RepositoryView = view(events)): ContextState = events.fold(ContextState(OWNER)) { s, e ->
        ContextReconciler().ingest(s, e, view).state
    }
}

/** Test port: copy-on-commit rollback and CAS; it does not simulate Android/SQLite execution. */
internal class MemoryPort(var snapshot: ContextSnapshot) : ContextRepositoryPort {
    var failSave = false
    var writes = 0
    @Synchronized override fun <T> transaction(block: (ContextTransaction) -> T): T {
        var pending = snapshot
        var pendingWrites = 0
        val result = block(object : ContextTransaction {
            override fun load() = pending
            override fun saveCandidates(expectedRevision: Long, state: ContextState) {
                check(pending.state.revision == expectedRevision) { "CANDIDATE_CAS_FAILED" }
                pending = pending.copy(state = state)
                pendingWrites++
                if (failSave) error("SYNTHETIC_WRITE_FAILURE")
            }
        })
        snapshot = pending
        writes += pendingWrites
        return result
    }
}
