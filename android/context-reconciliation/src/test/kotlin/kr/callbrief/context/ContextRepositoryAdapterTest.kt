package kr.callbrief.context

import kotlin.test.*
import kr.callbrief.context.SyntheticFixtures.OWNER
import kr.callbrief.context.SyntheticFixtures.scope
import kr.callbrief.context.SyntheticFixtures.fences
import kr.callbrief.context.SyntheticFixtures.event
import kr.callbrief.context.SyntheticFixtures.view
import kr.callbrief.context.SyntheticFixtures.chain
import kr.callbrief.context.SyntheticFixtures.reduce

class ContextRepositoryAdapterTest {
    @Test fun savesCandidatesAndReopeningDoesNotDuplicateReceipts() {
        val events = chain()
        val port = MemoryPort(ContextSnapshot(ContextState(OWNER), view(events)))
        val adapter = ContextRepositoryAdapter(port)
        events.forEach { assertTrue(adapter.ingest(it).committed) }
        assertEquals(3, port.writes)
        assertEquals(6, port.snapshot.state.projection.items.size)
        assertEquals(reduce(events), port.snapshot.state)
        val reopened = ContextRepositoryAdapter(port)
        val before = port.snapshot
        events.forEach { assertEquals(ReceiptStatus.DUPLICATE, reopened.ingest(it).receipt?.status) }
        assertEquals(before, port.snapshot)
        assertEquals(3, port.writes)
    }

    @Test fun everyNativeFenceIsComparedAtFinalCommitIncludingRetries() {
        val e = event()
        val changedFences = listOf(fences.copy(user = 2), fences.copy(lock = 2), fences.copy(task = 2),
            fences.copy(board = 2), fences.copy(relation = 2), fences.copy(archive = 2), fences.copy(leaseEpoch = 2))
        for (changed in changedFences) for (retry in listOf(false, true)) {
            val port = MemoryPort(ContextSnapshot(ContextState(OWNER), view(listOf(e))))
            val adapter = ContextRepositoryAdapter(port)
            if (retry) assertTrue(adapter.ingest(e).committed)
            val prepared = adapter.prepare(e)
            val before = port.snapshot.state
            port.snapshot = port.snapshot.copy(view = port.snapshot.view.copy(fences = changed))
            assertEquals(HoldReason.REVISION_CONFLICT, adapter.commit(prepared).reason)
            assertEquals(before, port.snapshot.state)
        }
    }

    @Test fun sourcePolicyOwnerAndItemRevisionRaceRejectPreparedBatch() {
        val e = event()
        val original = view(listOf(e))
        val key = ItemKey(scope, Kind.DOCUMENT, "quote-v1")
        val mutations = listOf(original.copy(admitted = emptySet()), original.copy(owner = "other-owner"),
            original.copy(links = emptyList()), original.copy(archived = true), original.copy(hidden = true),
            original.copy(items = mapOf(key to CanonicalItem(2, mapOf(Field.STATUS to "DONE")))))
        for (changed in mutations) {
            val port = MemoryPort(ContextSnapshot(ContextState(OWNER), original))
            val adapter = ContextRepositoryAdapter(port)
            val prepared = adapter.prepare(e)
            port.snapshot = port.snapshot.copy(view = changed)
            assertFalse(adapter.commit(prepared).committed)
            assertEquals(0, port.writes)
            assertTrue(port.snapshot.state.events.isEmpty())
        }
    }

    @Test fun concurrentCandidatesUseCasAndCanBeRecomputedAfterConflict() {
        val events = chain()
        val port = MemoryPort(ContextSnapshot(ContextState(OWNER), view(events)))
        val adapter = ContextRepositoryAdapter(port)
        val a = adapter.prepare(events[0])
        val b = adapter.prepare(events[1])
        assertTrue(adapter.commit(a).committed)
        assertEquals(HoldReason.REVISION_CONFLICT, adapter.commit(b).reason)
        assertTrue(adapter.ingest(events[1]).committed)
        assertEquals(2, port.snapshot.state.events.size)
    }

    @Test fun failedTransactionLeavesLedgerReceiptAndCandidateTogetherUnchanged() {
        val events = chain()
        val port = MemoryPort(ContextSnapshot(ContextState(OWNER), view(events)))
        val adapter = ContextRepositoryAdapter(port)
        assertTrue(adapter.ingest(events[0]).committed)
        val before = port.snapshot
        port.failSave = true
        assertFailsWith<IllegalStateException> { adapter.ingest(events[1]) }
        assertEquals(before, port.snapshot)
        port.failSave = false
        assertTrue(adapter.ingest(events[1]).committed)
        assertEquals(2, port.snapshot.state.events.size)
        assertEquals(2, port.snapshot.state.receipts.size)
    }

    @Test fun revocationRefreshRemovesCachedCandidatesAndStaleDuplicateIsRejected() {
        val e = event()
        val port = MemoryPort(ContextSnapshot(ContextState(OWNER), view(listOf(e))))
        val adapter = ContextRepositoryAdapter(port)
        adapter.ingest(e)
        port.snapshot = port.snapshot.copy(view = port.snapshot.view.copy(admitted = emptySet()))
        val retry = adapter.ingest(e)
        assertFalse(retry.committed)
        assertEquals(HoldReason.SOURCE_UNAVAILABLE, retry.reason)
        assertTrue(adapter.commit(adapter.prepareRefresh()).committed)
        assertTrue(port.snapshot.state.projection.items.isEmpty())
        assertEquals(1, port.snapshot.state.events.size)
    }

    @Test fun duplicateRechecksManualDoneInsteadOfRestoringEarlierProposal() {
        val e = event(kind = Kind.TASK, values = mapOf(Field.STATUS to "OPEN", Field.DUE to "2027-01-01"))
        val port = MemoryPort(ContextSnapshot(ContextState(OWNER), view(listOf(e))))
        val adapter = ContextRepositoryAdapter(port)
        val first = adapter.ingest(e)
        val key = ItemKey(scope, Kind.TASK, "quote-v1")
        val done = CanonicalItem(2, mapOf(Field.STATUS to "DONE", Field.DUE to "2026-10-05"))
        port.snapshot = port.snapshot.copy(view = port.snapshot.view.copy(items = mapOf(key to done), fences = fences.copy(user = 2, task = 2)))
        val retry = adapter.ingest(e)
        assertTrue(retry.committed)
        assertEquals(first.receipt?.id, retry.receipt?.id)
        assertTrue(port.snapshot.state.projection.items.isEmpty())
        assertEquals(done, port.snapshot.view.items[key])
    }

    @Test fun invalidEventCannotCreateAnyCandidateOrReceipt() {
        val e = event().copy(body = "tampered")
        val port = MemoryPort(ContextSnapshot(ContextState(OWNER), view(listOf(e))))
        val result = ContextRepositoryAdapter(port).ingest(e)
        assertFalse(result.committed)
        assertEquals(HoldReason.INVALID_EVENT, result.reason)
        assertEquals(0, port.writes)
    }

    @Test fun callerMutationAfterPrepareCannotChangeSavedFacts() {
        val values = mutableMapOf<Field, String?>(Field.DUE to "2026-10-05")
        val e = event(values = values)
        val port = MemoryPort(ContextSnapshot(ContextState(OWNER), view(listOf(e))))
        val adapter = ContextRepositoryAdapter(port)
        val prepared = adapter.prepare(e)
        values[Field.DUE] = "2099-01-01"
        assertTrue(adapter.commit(prepared).committed)
        assertEquals("2026-10-05", port.snapshot.state.projection.items.values.single().fields.getValue(Field.DUE).value)
    }

    @Test fun candidateJsonIsDeterministicAndContainsReferencesInsteadOfBodiesOrEndpoints() {
        val events = chain()
        val state = reduce(events)
        val json = CandidateJson.encode(state, fences)
        assertEquals(json, CandidateJson.encode(reduce(events.reversed(), view(events)), fences))
        assertTrue(json.contains("\"canonicalWriteAllowed\":false"))
        assertTrue(json.contains(events[2].ref.sha256))
        assertTrue(json.contains("2026-10-09"))
        assertFalse(json.contains("contact@example.invalid"))
        assertFalse(json.contains(events[0].body))
        assertTrue(json.contains("\"sourceVersion\":1"))
    }

    @Test fun persistedCandidateCarriesExpectedItemRevisionAndEscapesText() {
        val e = event(values = mapOf(Field.TITLE to "합성 \"서류\"\n😀\\"))
        val key = ItemKey(scope, Kind.DOCUMENT, "quote-v1")
        val s = reduce(listOf(e), view(listOf(e)).copy(items = mapOf(key to CanonicalItem(12, emptyMap()))))
        val json = CandidateJson.encode(s, fences)
        assertTrue(json.contains("\"expectedItemRevision\":12"))
        assertTrue(json.contains("\\\"서류\\\"\\u000a\\ud83d\\ude00\\\\"))
    }
}
