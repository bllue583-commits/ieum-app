package kr.callbrief.context

/** Deterministic, owner-scoped reducer. It only writes the candidate state returned to its
 * caller; no database, network, wall clock, text parser or identity heuristic lives here. */
class ContextReconciler {
    fun ingest(state: ContextState, input: NormalizedEvent, view: RepositoryView): Reconciliation {
        val event = input.copy(claims = input.claims.map { it.copy(values = it.values.toMap()) })
        val key = event.ref.key
        fun rejected(reason: HoldReason) = Reconciliation(state,
            IngestReceipt(receiptId(event.ref), event.ref, ReceiptStatus.REJECTED, reason))
        if (state.owner != view.owner || key.source.owner != view.owner) return rejected(HoldReason.OWNER_MISMATCH)
        if (!valid(event)) return rejected(HoldReason.INVALID_EVENT)
        if (!admitted(event, view)) return rejected(HoldReason.SOURCE_UNAVAILABLE)
        val prior = state.events[key]
        val status: ReceiptStatus
        val events: Map<VersionKey, NormalizedEvent>
        var collisions = state.collisions
        if (prior != null) {
            events = state.events
            // Observation time is a collection cursor, not content or semantic precedence.
            if (prior.copy(observedAt = event.observedAt) == event) {
                status = if (key in collisions) ReceiptStatus.COLLISION else ReceiptStatus.DUPLICATE
            } else {
                status = ReceiptStatus.COLLISION
                collisions = collisions + (key to ((collisions[key] ?: setOf(prior)) + event))
            }
        } else {
            events = state.events + (key to event)
            status = if (state.events.keys.any { it.source == key.source && it.version > key.version })
                ReceiptStatus.HISTORICAL else ReceiptStatus.ACCEPTED
        }
        val receipt = IngestReceipt(state.receipts[key]?.id ?: receiptId(event.ref), event.ref, status,
            HoldReason.SOURCE_COLLISION.takeIf { status == ReceiptStatus.COLLISION })
        val next = state.copy(events = events, collisions = collisions,
            receipts = if (key in state.receipts) state.receipts else state.receipts + (key to receipt))
        val projected = refresh(next, view)
        // A true no-op retry keeps the persisted revision. Fresh fences still came from the port.
        return Reconciliation(projected.copy(revision = if (projected == state) state.revision else state.revision + 1), receipt)
    }

    /** Re-run on relation/protection/source-policy changes even if no new event arrived. */
    fun refresh(state: ContextState, view: RepositoryView): ContextState {
        require(state.owner == view.owner)
        val holds = linkedSetOf<Hold>()
        if (view.archived || view.hidden) return state.copy(projection = CandidateProjection(
            holds = setOf(Hold(HoldReason.ARCHIVED_OR_HIDDEN))))
        val heads = state.events.values.groupBy { it.ref.key.source }.values.map { versions ->
            versions.maxBy { it.ref.key.version }
        }
        val active = heads.filter { event ->
            val reason = when {
                event.ref.key in state.collisions -> HoldReason.SOURCE_COLLISION
                !admitted(event, view) -> HoldReason.SOURCE_UNAVAILABLE
                else -> null
            }
            if (reason != null) holds += Hold(reason, event.ref)
            reason == null && event.operation == Operation.UPSERT
        }.associateBy { it.ref }
        fun evidenceCurrent(e: Evidence): Boolean = active[e.source]?.let { quoteValid(it.body, e) } == true
        val linked = linkedMapOf<SourceRef, WorkScope>()
        active.values.forEach { event ->
            val links = view.links.filter { it.source == event.ref }.distinct()
            val reason = when {
                links.isEmpty() -> HoldReason.LINK_UNRESOLVED
                links.size != 1 -> HoldReason.LINK_AMBIGUOUS
                else -> {
                    val link = links.single()
                    when {
                        link.scope.owner != view.owner || link.scope.personId.isBlank() || link.scope.workId.isBlank() -> HoldReason.LINK_UNRESOLVED
                        link.relationRevision != view.fences.relation || link.reviewReceiptRef.isBlank() ||
                            !evidenceCurrent(link.personEvidence) || !evidenceCurrent(link.workEvidence) -> HoldReason.LINK_EVIDENCE_STALE
                        else -> null
                    }
                }
            }
            if (reason == null) linked[event.ref] = links.single().scope
            else holds += Hold(reason, event.ref)
        }
        val nodes = linkedMapOf<ClaimRef, Node>()
        active.values.forEach { event ->
            val scope = linked[event.ref] ?: return@forEach
            event.claims.forEach { claim ->
                val ref = ClaimRef(event.ref, claim.id)
                val node = Node(ref, event, claim, ItemKey(scope, claim.kind, claim.targetId))
                nodes[ref] = node
                if (claim.certainty != Certainty.CLEAR) holds += Hold(HoldReason.UNCERTAIN_CLAIM, claim = ref, item = node.item)
            }
        }
        val blocked = mutableSetOf<ClaimRef>()
        nodes.values.forEach { node ->
            if (node.claim.certainty != Certainty.CLEAR) blocked += node.ref
            if (node.claim.operation == ClaimOperation.CANCEL && node.claim.supersedes == null) {
                blocked += node.ref
                holds += Hold(HoldReason.CAUSAL_TARGET_PENDING, claim = node.ref, item = node.item)
            }
            listOfNotNull(node.claim.supersedes, node.claim.origin).forEach { reference ->
                val parent = nodes[reference]
                val reason = when {
                    reference.source.key.source.owner != view.owner -> HoldReason.CAUSAL_SCOPE_MISMATCH
                    parent == null -> HoldReason.CAUSAL_TARGET_PENDING
                    parent.item != node.item -> HoldReason.CAUSAL_SCOPE_MISMATCH
                    node.claim.supersedes == reference && node.claim.values.keys.intersect(parent.claim.values.keys).isEmpty() -> HoldReason.CAUSAL_SCOPE_MISMATCH
                    node.claim.origin == reference && (node.claim.values != parent.claim.values ||
                        node.claim.evidence.quote != parent.claim.evidence.quote || node.claim.operation != parent.claim.operation) -> HoldReason.ORIGIN_MISMATCH
                    else -> null
                }
                if (reason != null) {
                    blocked += node.ref
                    holds += Hold(reason, claim = node.ref, item = node.item)
                }
            }
        }
        fun dependencies(node: Node) = listOfNotNull(node.claim.supersedes, node.claim.origin)
        fun reaches(from: ClaimRef, to: ClaimRef, visited: MutableSet<ClaimRef>): Boolean {
            if (!visited.add(from)) return false
            return nodes[from]?.let { node -> dependencies(node).any { it == to || reaches(it, to, visited) } } == true
        }
        nodes.values.forEach { node ->
            if (reaches(node.ref, node.ref, mutableSetOf())) {
                blocked += node.ref
                holds += Hold(HoldReason.CAUSAL_CYCLE, claim = node.ref, item = node.item)
            }
        }
        // A dependent claim cannot launder a held/uncertain/cyclic origin into current state.
        var changed: Boolean
        do {
            changed = false
            nodes.values.filter { it.ref !in blocked }.forEach { node ->
                if (dependencies(node).any { it in blocked }) {
                    blocked += node.ref
                    holds += Hold(HoldReason.CAUSAL_TARGET_PENDING, claim = node.ref, item = node.item)
                    changed = true
                }
            }
        } while (changed)

        fun origin(node: Node): ClaimRef = node.claim.origin?.let { origin(nodes.getValue(it)) } ?: node.ref
        val items = linkedMapOf<ItemKey, CandidateItem>()
        nodes.values.filter { it.ref !in blocked }.groupBy { it.item }.forEach { (item, candidates) ->
            val current = view.items[item]
            val protection = when {
                current?.values?.get(Field.STATUS) == "DONE" -> HoldReason.MANUAL_DONE
                current?.locked == true -> HoldReason.LOCKED
                current?.manuallyEdited == true -> HoldReason.MANUAL_EDIT
                else -> null
            }
            if (protection != null) {
                holds += Hold(protection, item = item)
                return@forEach
            }
            val fields = linkedMapOf<Field, FactValue>()
            candidates.flatMap { it.claim.values.keys }.distinct().sortedBy { it.name }.forEach { field ->
                val assertions = candidates.filter { field in it.claim.values }
                // Causal order is scoped to this target/field. An unrelated reply cannot replace it.
                val maxima = assertions.filter { a -> assertions.none { b ->
                    b != a && when {
                        origin(a) == origin(b) -> false
                        reaches(origin(b), origin(a), mutableSetOf()) -> true
                        reaches(origin(a), origin(b), mutableSetOf()) -> false
                        // Copies retain their origin's effective time, not forwarding time.
                        else -> {
                            val at = nodes.getValue(origin(a)).event.occurredAt
                            val bt = nodes.getValue(origin(b)).event.occurredAt
                            at != null && bt != null && bt > at
                        }
                    }
                } }
                if (maxima.isEmpty()) {
                    holds += Hold(HoldReason.FACT_CONFLICT, item = item, field = field)
                } else if (maxima.any { nodes.getValue(origin(it)).event.occurredAt == null && nodes.getValue(origin(it)).claim.supersedes == null }) {
                    holds += Hold(HoldReason.UNKNOWN_TIME, item = item, field = field)
                } else if (maxima.map { it.claim.values[field] }.distinct().size != 1) {
                    holds += Hold(HoldReason.FACT_CONFLICT, item = item, field = field)
                } else {
                    fields[field] = FactValue(maxima.first().claim.values[field], maxima.map { it.ref }.toSet(),
                        maxima.map { origin(it) }.toSet())
                }
            }
            if (fields.isNotEmpty()) items[item] = CandidateItem(fields, current?.revision)
        }
        return state.copy(projection = CandidateProjection(items.toMap(), holds.toSet(), active.keys.toSet()))
    }

    private data class Node(val ref: ClaimRef, val event: NormalizedEvent, val claim: Claim, val item: ItemKey)

    private fun admitted(event: NormalizedEvent, view: RepositoryView) =
        AdmittedSource(event.ref, event.localBindingRef, view.fences.leaseEpoch) in view.admitted

    private fun valid(e: NormalizedEvent): Boolean {
        val s = e.ref.key.source
        if (listOf(s.owner, s.namespace, s.externalId, e.localBindingRef).any { it.isBlank() || it.length > 512 } ||
            e.ref.key.version < 1 || sha256(e.body) != e.ref.sha256 || e.body.length > 1_000_000 ||
            e.claims.size > 200 || e.claims.map { it.id }.distinct().size != e.claims.size) return false
        if (e.operation == Operation.TOMBSTONE) return e.body.isEmpty() && e.claims.isEmpty()
        return e.claims.all { c ->
            c.id.isNotBlank() && c.targetId.isNotBlank() && c.id.length <= 512 && c.targetId.length <= 512 &&
                c.values.isNotEmpty() && c.evidence.source == e.ref && quoteValid(e.body, c.evidence) &&
                c.values.values.all { it == null || it.isNotBlank() && it.length <= 4096 } &&
                (Field.STATUS !in c.values || c.values[Field.STATUS] in setOf("OPEN", "WAITING", "IN_PROGRESS", "NEEDS_CONFIRMATION", "DONE", "CANCELLED", "REQUIRED", "RECEIVED")) &&
                (c.operation != ClaimOperation.CANCEL || c.values == mapOf(Field.STATUS to "CANCELLED")) &&
                (c.values[Field.STATUS] != "CANCELLED" || c.operation == ClaimOperation.CANCEL)
        }
    }

    private fun receiptId(ref: SourceRef): String {
        val s = ref.key.source
        // Length framing avoids identity collisions even when a provider id contains separators.
        return sha256(listOf(s.owner, s.channel.name, s.namespace, s.externalId, ref.key.version.toString(), ref.sha256)
            .joinToString("") { "${it.length}:$it" })
    }

    private fun quoteValid(body: String, e: Evidence): Boolean = e.start >= 0 && e.end > e.start &&
        e.end <= body.codePointCount(0, body.length) &&
        body.substring(body.offsetByCodePoints(0, e.start), body.offsetByCodePoints(0, e.end)) == e.quote
}
