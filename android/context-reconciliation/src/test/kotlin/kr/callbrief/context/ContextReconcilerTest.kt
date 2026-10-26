package kr.callbrief.context

import java.time.Instant
import kotlin.test.*
import kr.callbrief.context.SyntheticFixtures.OWNER
import kr.callbrief.context.SyntheticFixtures.scope
import kr.callbrief.context.SyntheticFixtures.fences
import kr.callbrief.context.SyntheticFixtures.event
import kr.callbrief.context.SyntheticFixtures.view
import kr.callbrief.context.SyntheticFixtures.chain
import kr.callbrief.context.SyntheticFixtures.reduce
import kr.callbrief.context.SyntheticFixtures.link

class ContextReconcilerTest {
    private val engine = ContextReconciler()
    private fun ContextState.value(target: String, field: Field): String? = projection.items.entries
        .single { it.key.targetId == target }.value.fields[field]?.value
    private fun ContextState.reasons() = projection.holds.map { it.reason }.toSet()

    @Test fun callSmsMailProduceCumulativeReviewableContext() {
        val events = chain()
        val s = reduce(events)
        assertEquals(6, s.projection.items.size)
        assertEquals("RECEIVED", s.value("quote-v1", Field.STATUS))
        assertEquals("2", s.value("quote-v2", Field.EDITION))
        assertEquals("2026-10-09", s.value("quote-v2", Field.DUE))
        assertEquals("REQUIRED", s.value("registration", Field.STATUS))
        assertEquals("2026-10-09T10:00:00+09:00", s.value("visit", Field.DUE))
        assertEquals("2026-10-09", s.value("submit", Field.DUE))
        assertEquals("WAITING", s.value("work", Field.STATUS))
        assertTrue(s.projection.holds.isEmpty())
        assertFalse(s.projection.canonicalWriteAllowed)
        val due = s.projection.items.entries.single { it.key.targetId == "quote-v2" }.value.fields.getValue(Field.DUE)
        assertEquals(setOf(ClaimRef(events[2].ref, "due-change")), due.provenance)
        val ref = due.provenance.single()
        assertEquals(events[2].body, s.events.getValue(ref.source.key).claims.single { it.id == ref.claimId }.evidence.quote)
    }

    @Test fun allSixArrivalOrdersConvergeWithoutUsingCollectionTime() {
        val events = chain()
        val expected = reduce(events).projection
        for (a in events.indices) for (b in events.indices.filter { it != a }) {
            val c = events.indices.single { it != a && it != b }
            assertEquals(expected, reduce(listOf(events[a], events[b], events[c]), view(events)).projection)
        }
    }

    @Test fun replyArrivingBeforeItsTargetStaysPendingThenResolves() {
        val events = chain()
        val first = engine.ingest(ContextState(OWNER), events[2], view(events)).state
        assertTrue(HoldReason.CAUSAL_TARGET_PENDING in first.reasons())
        assertNull(first.projection.items.entries.find { it.key.targetId == "quote-v2" })
        val next = engine.ingest(engine.ingest(first, events[1], view(events)).state, events[0], view(events)).state
        assertEquals(reduce(events).projection, next.projection)
    }

    @Test fun duplicateReturnsOriginalReceiptAndDoesNotAdvanceRevision() {
        val e = event()
        val first = engine.ingest(ContextState(OWNER), e, view(listOf(e)))
        val retry = engine.ingest(first.state, e.copy(observedAt = e.observedAt.plusSeconds(90)), view(listOf(e)))
        assertEquals(ReceiptStatus.DUPLICATE, retry.receipt.status)
        assertEquals(first.receipt.id, retry.receipt.id)
        assertEquals(first.state, retry.state)
    }

    @Test fun sameVersionDifferentBodyIsQuarantinedInEitherOrder() {
        val a = event()
        val b = event(body = "다른 합성 내용", values = mapOf(Field.STATUS to "RECEIVED"))
        for (order in listOf(listOf(a, b), listOf(b, a))) {
            val s = reduce(order, view(order, listOf(link(a), link(b))))
            assertTrue(s.projection.items.isEmpty())
            assertTrue(HoldReason.SOURCE_COLLISION in s.reasons())
            assertEquals(2, s.collisions.getValue(a.ref.key).size)
            assertEquals(ReceiptStatus.COLLISION, engine.ingest(s, a, view(order)).receipt.status)
        }
    }

    @Test fun sameHashButChangedClaimsOrBindingCannotBypassCollision() {
        val a = event()
        val b = a.copy(claims = listOf(a.claims.single().copy(values = mapOf(Field.DUE to "2026-12-31"))))
        assertTrue(HoldReason.SOURCE_COLLISION in reduce(listOf(a, b)).reasons())
        val c = a.copy(localBindingRef = "different-local-binding")
        assertTrue(HoldReason.SOURCE_COLLISION in reduce(listOf(a, c)).reasons())
    }

    @Test fun higherRevisionReplacesPreviousClaimsAndResolvesOlderCollision() {
        val a = event()
        val collision = event(body = "충돌")
        val b = event(version = 2, body = "수정 합성 내용", values = mapOf(Field.STATUS to "RECEIVED"))
        val s = reduce(listOf(a, collision, b))
        assertEquals("RECEIVED", s.value("quote-v1", Field.STATUS))
        assertNull(s.value("quote-v1", Field.DUE))
        assertEquals(2, s.events.size)
        assertTrue(s.projection.holds.isEmpty())
    }

    @Test fun tombstoneInvalidatesDerivedValuesAndLateRevisionCannotResurrect() {
        val a = event()
        val deleted = event(version = 3, body = "", operation = Operation.TOMBSTONE)
        val late = event(version = 2, body = "늦은 수정")
        val s = reduce(listOf(deleted, a, late))
        assertTrue(s.projection.items.isEmpty())
        assertEquals(3, s.events.size)
        assertEquals(ReceiptStatus.HISTORICAL, s.receipts.getValue(late.ref.key).status)
    }

    @Test fun businessCancellationIsDistinctFromSourceDeletionAndTaskCompletion() {
        val a = event(kind = Kind.TASK, values = mapOf(Field.STATUS to "OPEN", Field.DUE to "2026-10-05"))
        val b = event("cancel", Channel.MAIL, at = "2026-10-04T00:00:00Z", kind = Kind.TASK,
            values = mapOf(Field.STATUS to "CANCELLED")).let { it.copy(claims = listOf(it.claims.single().copy(
                operation = ClaimOperation.CANCEL, supersedes = ClaimRef(a.ref, "claim-1")))) }
        val s = reduce(listOf(a, b))
        assertEquals("CANCELLED", s.value("quote-v1", Field.STATUS))
        assertEquals("2026-10-05", s.value("quote-v1", Field.DUE))
        assertEquals(2, s.projection.activeSources.size)
    }

    @Test fun oldOccurrenceCollectedLaterCannotOverrideCurrentFact() {
        val a = event("new", at = "2026-10-05T00:00:00Z", values = mapOf(Field.DUE to "2026-10-09"))
        val b = event("old", at = "2026-10-01T00:00:00Z", values = mapOf(Field.DUE to "2026-10-02"))
            .copy(observedAt = Instant.parse("2026-10-30T00:00:00Z"))
        assertEquals("2026-10-09", reduce(listOf(a, b)).value("quote-v1", Field.DUE))
    }

    @Test fun sourceRevisionsAreNeverComparedAcrossDifferentSources() {
        val a = event("old", version = 100, values = mapOf(Field.DUE to "2026-10-02"))
        val b = event("new", at = "2026-10-05T00:00:00Z", values = mapOf(Field.DUE to "2026-10-09"))
        assertEquals("2026-10-09", reduce(listOf(b, a)).value("quote-v1", Field.DUE))
    }

    @Test fun equalTimeContradictionHoldsOnlyTheConflictingField() {
        val a = event(values = mapOf(Field.STATUS to "REQUIRED", Field.DUE to "2026-10-05"))
        val b = event("mail", Channel.MAIL, values = mapOf(Field.STATUS to "REQUIRED", Field.DUE to "2026-10-09"))
        val s = reduce(listOf(a, b))
        assertEquals("REQUIRED", s.value("quote-v1", Field.STATUS))
        assertNull(s.value("quote-v1", Field.DUE))
        assertEquals(setOf(HoldReason.FACT_CONFLICT), s.reasons())
    }

    @Test fun explicitCausalityResolvesEqualTimeAndMissingTime() {
        val a = event()
        for (at in listOf("2026-10-01T00:00:00Z", null)) {
            val b = event("change", Channel.MAIL, at = at, values = mapOf(Field.DUE to "2026-10-09"))
                .let { it.copy(claims = listOf(it.claims.single().copy(supersedes = ClaimRef(a.ref, "claim-1")))) }
            assertEquals("2026-10-09", reduce(listOf(a, b)).value("quote-v1", Field.DUE))
        }
    }

    @Test fun unknownTimeWithoutCausalityCannotBecomeCurrentEvenWhenCollectedLast() {
        val a = event()
        val b = event("unknown", Channel.MAIL, at = null, values = mapOf(Field.DUE to "2026-10-09"))
        val s = reduce(listOf(a, b))
        assertNull(s.value("quote-v1", Field.DUE))
        assertTrue(HoldReason.UNKNOWN_TIME in s.reasons())
    }

    @Test fun nameAndEndpointAloneNeverResolveIdentityOrWork() {
        val e = event(body = "김담당 W-1입니다")
        val s = reduce(listOf(e), view(listOf(e), emptyList()))
        assertTrue(s.projection.items.isEmpty())
        assertTrue(HoldReason.LINK_UNRESOLVED in s.reasons())
    }

    @Test fun multipleReviewedCandidatesRemainUnresolved() {
        val e = event()
        val s = reduce(listOf(e), view(listOf(e), listOf(link(e), link(e, scope.copy(personId = "other-person")))))
        assertTrue(s.projection.items.isEmpty())
        assertTrue(HoldReason.LINK_AMBIGUOUS in s.reasons())
    }

    @Test fun staleOrForgedIdentityEvidenceCannotLink() {
        val e = event()
        for (l in listOf(link(e).copy(relationRevision = 0), link(e).copy(reviewReceiptRef = ""),
            link(e).copy(personEvidence = e.claims.single().evidence.copy(quote = "forged")))) {
            val s = reduce(listOf(e), view(listOf(e), listOf(l)))
            assertTrue(s.projection.items.isEmpty())
            assertTrue(HoldReason.LINK_EVIDENCE_STALE in s.reasons())
        }
    }

    @Test fun ownerMismatchIsRejectedBeforeStorage() {
        val e = event()
        val wrong = view(listOf(e)).copy(owner = "owner-other")
        val r = engine.ingest(ContextState(wrong.owner), e, wrong)
        assertEquals(HoldReason.OWNER_MISMATCH, r.receipt.reason)
        assertTrue(r.state.events.isEmpty())
    }

    @Test fun causalReferencesCannotCrossWorkOrOwner() {
        val a = event()
        val b = event("mail", Channel.MAIL).let { it.copy(claims = listOf(it.claims.single().copy(supersedes = ClaimRef(a.ref, "claim-1")))) }
        val s = reduce(listOf(a, b), view(listOf(a, b), listOf(link(a), link(b, scope.copy(workId = "other-work")))))
        assertTrue(HoldReason.CAUSAL_SCOPE_MISMATCH in s.reasons())
        val foreignRef = a.ref.copy(key = a.ref.key.copy(source = a.ref.key.source.copy(owner = "other-owner")))
        val c = b.copy(claims = listOf(b.claims.single().copy(supersedes = ClaimRef(foreignRef, "claim-1"))))
        assertTrue(HoldReason.CAUSAL_SCOPE_MISMATCH in reduce(listOf(a, c)).reasons())
    }

    @Test fun uncertainClaimsAndTheirDependentsStayHeld() {
        val a = event().let { it.copy(claims = listOf(it.claims.single().copy(certainty = Certainty.ESTIMATED))) }
        val b = event("reply").let { it.copy(claims = listOf(it.claims.single().copy(supersedes = ClaimRef(a.ref, "claim-1")))) }
        val s = reduce(listOf(a, b))
        assertTrue(s.projection.items.isEmpty())
        assertEquals(setOf(HoldReason.UNCERTAIN_CLAIM, HoldReason.CAUSAL_TARGET_PENDING), s.reasons())
    }

    @Test fun causalCyclesAreHeldWithoutRecursionFailure() {
        val a = event("a")
        val b = event("b")
        val aa = a.copy(claims = listOf(a.claims.single().copy(supersedes = ClaimRef(b.ref, "claim-1"))))
        val bb = b.copy(claims = listOf(b.claims.single().copy(supersedes = ClaimRef(a.ref, "claim-1"))))
        val s = reduce(listOf(aa, bb))
        assertTrue(s.projection.items.isEmpty())
        assertTrue(HoldReason.CAUSAL_CYCLE in s.reasons())
    }

    @Test fun equalHashDifferentChannelsRemainDistinctSources() {
        val a = event()
        val b = event("mail", Channel.MAIL)
        assertEquals(a.ref.sha256, b.ref.sha256)
        val s = reduce(listOf(a, b))
        assertEquals(2, s.events.size)
        val fact = s.projection.items.values.single().fields.getValue(Field.STATUS)
        assertEquals(2, fact.origins.size)
        assertEquals(2, fact.provenance.size)
    }

    @Test fun quotedCopiesCountOneOriginAndRetainBothSources() {
        val a = event()
        val b = event("copy", Channel.MAIL, at = "2026-10-20T00:00:00Z").let {
            it.copy(claims = listOf(it.claims.single().copy(origin = ClaimRef(a.ref, "claim-1")))) }
        val s = reduce(listOf(a, b))
        val fact = s.projection.items.values.single().fields.getValue(Field.DUE)
        assertEquals(setOf(ClaimRef(a.ref, "claim-1")), fact.origins)
        assertEquals(2, fact.provenance.size)
        val change = event("change", at = "2026-10-05T00:00:00Z", values = mapOf(Field.DUE to "2026-10-09"))
        assertEquals("2026-10-09", reduce(listOf(a, change, b)).value("quote-v1", Field.DUE))
    }

    @Test fun copiedQuoteMustMatchOriginalFactAndEvidence() {
        val a = event()
        val b = event("copy", values = mapOf(Field.DUE to "2099-01-01")).let {
            it.copy(claims = listOf(it.claims.single().copy(origin = ClaimRef(a.ref, "claim-1")))) }
        val s = reduce(listOf(a, b))
        assertTrue(HoldReason.ORIGIN_MISMATCH in s.reasons())
        assertEquals("2026-10-05", s.value("quote-v1", Field.DUE))
    }

    @Test fun manualDoneProtectsAllFieldsAgainstReopenAndDueChange() {
        val e = event(kind = Kind.TASK, values = mapOf(Field.STATUS to "OPEN", Field.DUE to "2027-01-01", Field.ASSIGNEE to "other"))
        val key = ItemKey(scope, Kind.TASK, "quote-v1")
        val original = CanonicalItem(7, mapOf(Field.STATUS to "DONE", Field.DUE to "2026-10-05", Field.ASSIGNEE to "me"))
        val v = view(listOf(e)).copy(items = mapOf(key to original))
        val s = reduce(listOf(e), v)
        assertTrue(s.projection.items.isEmpty())
        assertEquals(setOf(HoldReason.MANUAL_DONE), s.reasons())
        assertEquals(original, v.items[key])
    }

    @Test fun manualEditsAndLocksHoldWholeItemWhileOtherItemsRemainCandidates() {
        val events = chain()
        val key = ItemKey(scope, Kind.DOCUMENT, "quote-v2")
        for ((item, reason) in listOf(CanonicalItem(2, emptyMap(), manuallyEdited = true) to HoldReason.MANUAL_EDIT,
            CanonicalItem(2, emptyMap(), locked = true) to HoldReason.LOCKED)) {
            val s = reduce(events, view(events).copy(items = mapOf(key to item)))
            assertEquals(5, s.projection.items.size)
            assertFalse(key in s.projection.items)
            assertTrue(reason in s.reasons())
        }
    }

    @Test fun archiveAndHiddenSuppressAllBodyDerivedCandidates() {
        val e = event()
        for (v in listOf(view(listOf(e)).copy(archived = true), view(listOf(e)).copy(hidden = true))) {
            val s = reduce(listOf(e), v)
            assertTrue(s.projection.items.isEmpty())
            assertTrue(s.projection.activeSources.isEmpty())
            assertEquals(setOf(HoldReason.ARCHIVED_OR_HIDDEN), s.reasons())
        }
    }

    @Test fun withdrawnSourceAndStaleLeaseInvalidateCandidatesOnRefresh() {
        val e = event()
        val s = reduce(listOf(e))
        for (v in listOf(view(listOf(e)).copy(admitted = emptySet()), view(listOf(e)).copy(fences = fences.copy(leaseEpoch = 2)))) {
            val refreshed = engine.refresh(s, v)
            assertTrue(refreshed.projection.items.isEmpty())
            assertEquals(setOf(HoldReason.SOURCE_UNAVAILABLE), refreshed.reasons())
        }
    }

    @Test fun correctionInvalidatesLinksAndClaimsDependingOnOldEvidence() {
        val a = event()
        val b = event("reply").let { it.copy(claims = listOf(it.claims.single().copy(supersedes = ClaimRef(a.ref, "claim-1")))) }
        val correction = event(version = 2, body = "수정된 담당 정보")
        val s = reduce(listOf(a, b, correction))
        assertTrue(HoldReason.CAUSAL_TARGET_PENDING in s.reasons())
        val v = view(listOf(a, b, correction), listOf(link(correction).copy(personEvidence = a.claims.single().evidence), link(b)))
        assertTrue(HoldReason.LINK_EVIDENCE_STALE in engine.refresh(s, v).reasons())
    }

    @Test fun unicodeCodePointEvidenceAndExactHashAreChecked() {
        val e = event(body = "☎😀 합성 견적서")
        val quote = EventAdapter.evidence(e.ref, e.body, 2, 5)
        assertEquals(" 합성", quote.quote)
        val good = e.copy(claims = listOf(e.claims.single().copy(evidence = quote)))
        assertEquals(ReceiptStatus.ACCEPTED, engine.ingest(ContextState(OWNER), good, view(listOf(good))).receipt.status)
        for (bad in listOf(good.copy(body = good.body + " "), good.copy(claims = listOf(good.claims.single().copy(evidence = quote.copy(start = 3)))),
            good.copy(ref = good.ref.copy(sha256 = "0".repeat(64))))) {
            assertEquals(HoldReason.INVALID_EVENT, engine.ingest(ContextState(OWNER), bad, view(listOf(bad))).receipt.reason)
        }
    }

    @Test fun invalidCancellationAndDuplicateClaimIdsAreRejected() {
        val e = event()
        val bad = listOf(e.copy(claims = e.claims + e.claims),
            e.copy(claims = listOf(e.claims.single().copy(operation = ClaimOperation.CANCEL))),
            e.copy(claims = listOf(e.claims.single().copy(values = mapOf(Field.STATUS to "CANCELLED")))))
        bad.forEach { assertEquals(HoldReason.INVALID_EVENT, engine.ingest(ContextState(OWNER), it, view(listOf(it))).receipt.reason) }
    }

    @Test fun cancellationWithoutTargetDoesNotInventCancelledBusinessItem() {
        val e = event(values = mapOf(Field.STATUS to "CANCELLED")).let {
            it.copy(claims = listOf(it.claims.single().copy(operation = ClaimOperation.CANCEL))) }
        val s = reduce(listOf(e))
        assertTrue(s.projection.items.isEmpty())
        assertEquals(setOf(HoldReason.CAUSAL_TARGET_PENDING), s.reasons())
    }

    @Test fun contradictoryCausalAndTimeOrderingIsHeldInsteadOfPickingArbitraryWinner() {
        val a = event("a", at = "2026-10-03T00:00:00Z", values = mapOf(Field.DUE to "2026-10-03"))
        val b = event("b", at = "2026-10-01T00:00:00Z", values = mapOf(Field.DUE to "2026-10-01")).let {
            it.copy(claims = listOf(it.claims.single().copy(supersedes = ClaimRef(a.ref, "claim-1")))) }
        val c = event("c", at = "2026-10-02T00:00:00Z", values = mapOf(Field.DUE to "2026-10-02"))
        val s = reduce(listOf(a, b, c))
        assertTrue(s.projection.items.isEmpty())
        assertEquals(setOf(HoldReason.FACT_CONFLICT), s.reasons())
    }
}
