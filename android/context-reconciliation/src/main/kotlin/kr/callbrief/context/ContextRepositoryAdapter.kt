package kr.callbrief.context

data class ContextSnapshot(val state: ContextState, val view: RepositoryView)

/** Implement against the existing protected repository transaction. load/save must share
 * that transaction and owner. Commit is atomic; an exception must roll back all writes.
 * Implementations must persist the full ledger/collisions/receipts plus replacement candidate
 * projection. They must not translate saveCandidates into a canonical business update. */
interface ContextRepositoryPort {
    fun <T> transaction(block: (ContextTransaction) -> T): T
}
interface ContextTransaction {
    fun load(): ContextSnapshot
    fun saveCandidates(expectedRevision: Long, state: ContextState)
}

class PreparedContext internal constructor(
    internal val expected: ContextSnapshot,
    internal val next: ContextState,
    val receipt: IngestReceipt?,
) {
    val projection: CandidateProjection get() = next.projection
}

data class CommitResult(val committed: Boolean, val receipt: IngestReceipt?, val reason: HoldReason? = null)

/** A candidate-only adapter. There is intentionally no applyCanonical or trusted=true API.
 * Production model execution, native receipt/owner/lease validation and explicit user review
 * remain responsibilities of the existing Store.accept / workflow review paths. */
class ContextRepositoryAdapter(private val port: ContextRepositoryPort,
                               private val reconciler: ContextReconciler = ContextReconciler()) {
    fun prepare(event: NormalizedEvent): PreparedContext = port.transaction { tx ->
        val before = detached(tx.load())
        val result = reconciler.ingest(before.state, event, before.view)
        PreparedContext(before, result.state, result.receipt)
    }

    fun prepareRefresh(): PreparedContext = port.transaction { tx ->
        val before = detached(tx.load())
        val next = reconciler.refresh(before.state, before.view)
        PreparedContext(before, next.copy(revision = before.state.revision + if (next == before.state) 0 else 1), null)
    }

    fun commit(prepared: PreparedContext): CommitResult = port.transaction { tx ->
        val current = tx.load()
        if (current != prepared.expected) return@transaction CommitResult(false, prepared.receipt, HoldReason.REVISION_CONFLICT)
        if (prepared.receipt?.status == ReceiptStatus.REJECTED)
            return@transaction CommitResult(false, prepared.receipt, prepared.receipt.reason)
        if (prepared.next != current.state) tx.saveCandidates(current.state.revision, detached(
            ContextSnapshot(prepared.next, current.view)).state)
        CommitResult(true, prepared.receipt)
    }

    fun ingest(event: NormalizedEvent): CommitResult = commit(prepare(event))

    private fun detached(s: ContextSnapshot): ContextSnapshot {
        fun event(e: NormalizedEvent) = e.copy(claims = e.claims.map { it.copy(values = it.values.toMap()) })
        val p = s.state.projection
        return ContextSnapshot(s.state.copy(
            events = s.state.events.mapValues { event(it.value) },
            collisions = s.state.collisions.mapValues { (_, events) -> events.map { event(it) }.toSet() },
            receipts = s.state.receipts.toMap(),
            projection = p.copy(items = p.items.mapValues { (_, item) -> item.copy(fields = item.fields.mapValues { (_, f) ->
                f.copy(provenance = f.provenance.toSet(), origins = f.origins.toSet())
            }) }, holds = p.holds.toSet(), activeSources = p.activeSources.toSet())),
            s.view.copy(admitted = s.view.admitted.toSet(), links = s.view.links.toList(),
                items = s.view.items.mapValues { (_, item) -> item.copy(values = item.values.toMap()) }))
    }
}
