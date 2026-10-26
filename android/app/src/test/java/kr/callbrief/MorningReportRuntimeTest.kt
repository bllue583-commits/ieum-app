package kr.callbrief

import android.content.Context
import kr.callbrief.kakao.core.DeliveryCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** All data, cursors, receipts and ACKs here are synthetic; no SDK/network/message calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MorningReportRuntimeTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var ledger:DailyReportLedger
    private val profile="synthetic_morning_profile"
    private val day=LocalDate.parse("2026-10-26")
    private var env=ReportEnvironment(Instant.parse("2026-10-25T21:00:00Z"),ZoneId.of("America/Los_Angeles"),elapsed=1000,boot=1)
    private var current=true
    private var contentRevision=1L
    private var snapshotCalls=0
    private var syncCalls=0
    private var deliveryCalls=0
    private var beforeSnapshot:()->Unit={}
    private var beforeDelivery:()->Unit={}
    private var failRead=false
    private var invalid=false
    private var coverage=MorningCoverage.COMPLETE
    private var empty=false
    private var syncState=MorningSyncState.COMPLETE
    private var deliveryState=MorningDeliveryState.DRY_RUN_RECORDED
    private var wrongAck=false
    private val requests=mutableListOf<MorningSyncRequest>()
    private val cipher=object:ReportPayloadCipher {
        // Public deterministic host-only fixture cipher; sealed DB shadow still verifies persistence.
        override fun seal(text:String,aad:String,hasExistingPayload:Boolean)=(DeliveryCoordinator.sha256(aad)+text).toByteArray()
        override fun open(bytes:ByteArray,aad:String):String { val text=String(bytes);check(text.startsWith(DeliveryCoordinator.sha256(aad)));return text.drop(64) }
    }
    private fun snapshot(window:MorningReportWindow):MorningSnapshot {
        snapshotCalls++;beforeSnapshot();if(failRead)error("SYNTHETIC_READ_FAILURE")
        val text="합성 업무 $contentRevision · 수동 DONE 보호"
        val facts=if(empty)emptyList() else listOf(ReviewedReportSource("SYN-source-1","a".repeat(64),1,contentRevision,
            DeliveryCoordinator.sha256(text),if(invalid)"" else "SYN-USER-REVIEW",contentRevision,1,1,text,manualStatus="done"))
        return MorningSnapshot(DailyReportInput(if(empty)ReportReadiness.NO_RECORDINGS else ReportReadiness.REVIEWED,
            facts,facts.size,0,DeliveryCoordinator.sha256("synthetic-policy")),coverage,"합성 승인 범위")
    }
    private fun coordinator()=MorningReportCoordinator(ledger.morning(),MorningSnapshotPort(::snapshot),MorningIncrementalSyncPort { request->
        syncCalls++;requests+=request
        if(syncState==MorningSyncState.COMPLETE)MorningSyncResult(syncState,"SYN-cursor-$syncCalls",true) else MorningSyncResult(syncState)
    },MorningDeliveryPort { e->
        deliveryCalls++;beforeDelivery()
        if(deliveryState==MorningDeliveryState.ACKNOWLEDGED)MorningDeliveryResult(deliveryState,if(wrongAck)"SYN-wrong" else e.id,e.destination,e.bodyHash,"SYN-provider-ACK")
        else MorningDeliveryResult(deliveryState)
    },{env},{current})
    private fun tick()=runBlocking { coordinator().tick(ledger.config().generation) }
    private fun row(kind:MorningReportKind=MorningReportKind.DAILY,date:LocalDate=day)=requireNotNull(ledger.morning().get(MorningReportWindow(date,kind).key(profile)))
    private fun outbox()=ledger.morning().outbox()
    private fun advance(seconds:Long) { env=env.copy(now=env.now.plusSeconds(seconds),elapsed=env.elapsed+seconds*1000) }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(DailyReportLedger.DATABASE);context.deleteDatabase("ieum-daily-reports-v1.db")
        ledger=DailyReportLedger(context,profile,cipher);ledger.setEnabled(true,env.now,env.zone)
    }
    @After fun cleanup() { ledger.close() }

    @Test fun kstWindowsBoundariesYearLeapAndNextTargets() {
        val daily=MorningReportWindow(day,MorningReportKind.DAILY);val week=MorningReportWindow(day,MorningReportKind.ROLLING_7_DAY)
        assertEquals(Instant.parse("2026-10-24T15:00:00Z"),daily.from);assertEquals(Instant.parse("2026-10-25T15:00:00Z"),daily.until)
        assertTrue(daily.contains(daily.until.minusMillis(1)));assertFalse(daily.contains(daily.until));assertFalse(daily.contains(daily.from.minusMillis(1)))
        assertEquals(Instant.parse("2026-10-18T15:00:00Z"),week.from)
        assertEquals(day.minusDays(1),MorningReportWindow.latestDue(daily.due.minusNanos(1)));assertEquals(day,MorningReportWindow.latestDue(daily.due))
        assertEquals(LocalDate.parse("2026-12-31"),MorningReportWindow(LocalDate.parse("2027-01-01"),MorningReportKind.DAILY).from.atZone(MorningReportWindow.KST).toLocalDate())
        assertEquals(LocalDate.parse("2028-02-29"),MorningReportWindow(LocalDate.parse("2028-03-01"),MorningReportKind.DAILY).from.atZone(MorningReportWindow.KST).toLocalDate())
        assertEquals(Instant.parse("2026-10-26T14:00:00Z"),MorningReportWindow.nextTarget(env.now,23))
    }
    @Test fun deviceTimezoneChangeDuplicateTicksAndReopenKeepOneRevisionOutbox() {
        tick();assertEquals(2,outbox().size);assertTrue(outbox().all { it.state==MorningDeliveryState.DRY_RUN_RECORDED })
        repeat(4) { env=env.copy(zone=ZoneId.of(listOf("UTC","Asia/Seoul","America/New_York")[it%3]));tick() }
        ledger.close();ledger=DailyReportLedger(context,profile,cipher);tick()
        assertEquals(2,outbox().size);assertEquals(2,deliveryCalls);assertEquals(1,row().revision)
        assertEquals(2,ledger.morning().reports().size);assertFalse(outbox().any { it.state==MorningDeliveryState.ACKNOWLEDGED })
    }
    @Test fun offlineAndDozeCreateLocalPartialWithoutSyncOrRemoteWork() {
        env=env.copy(online=false,doze=true,batteryOkay=false);tick()
        assertEquals(0,syncCalls);assertEquals("GENERATED_PARTIAL",row().state);assertEquals("DEFERRED",ledger.morning().syncStatus())
        assertTrue(ledger.morning().body(row().key,1).contains("최신 동기화 미확인"));assertEquals(2,deliveryCalls)
        advance(901);env=env.copy(online=true,doze=false,batteryOkay=true);tick()
        assertEquals("GENERATED_COMPLETE",row().state);assertEquals(2,row().revision)
    }
    @Test fun knownEmptyAndUnknownEmptyHaveDifferentMeaning() {
        empty=true;tick();assertTrue(ledger.morning().body(row().key,1).contains("업무 데이터 없음"))
        coverage=MorningCoverage.UNKNOWN;tick()
        assertEquals(2,row().revision);assertEquals("GENERATED_PARTIAL",row().state)
        assertTrue(ledger.morning().body(row().key,2).contains("확정할 수 없음"))
    }
    @Test fun cursorOnlyChangesNeverCreateRevisionButLateVisibleDataDoes() {
        tick();val key=row().key;val original=ledger.morning().body(key,1)
        advance(901);tick();assertEquals(1,row().revision);assertTrue(ledger.morning().ingestCursor().startsWith("SYN-cursor"))
        contentRevision=2;tick();assertEquals(2,row().revision);assertEquals(original,ledger.morning().body(key,1));assertEquals(4,outbox().size)
        assertEquals(day,row().window.reportDay);assertTrue(ledger.morning().body(key,2).contains("업무 2"))
    }
    @Test fun primary23ThenPreReportUsesDurableCursorAndNoEarlyNextDay() {
        env=env.copy(now=Instant.parse("2026-10-25T14:00:00Z"));ledger.setEnabled(true,env.now,env.zone);tick()
        assertTrue(requests.any { it.reason=="23H_PRIMARY" });assertNull(ledger.morning().get(MorningReportWindow(day,MorningReportKind.DAILY).key(profile)))
        contentRevision=2;env=env.copy(now=Instant.parse("2026-10-25T21:00:00Z"),elapsed=env.elapsed+7*3600_000);tick()
        assertTrue(requests.any { it.reason=="PRE_REPORT" && it.durableCursor.isNotBlank() })
        assertTrue(ledger.morning().body(row().key,1).contains("업무 2"))
    }
    @Test fun acceptedAndFailedSyncCannotCommitCursorAndReuseOriginalId() {
        syncState=MorningSyncState.ACCEPTED;tick();assertEquals("",ledger.morning().ingestCursor())
        val request=requests.single();advance(901);syncState=MorningSyncState.FAILED;tick()
        assertEquals(request.id,requests.last().id);assertEquals(request.durableCursor,requests.last().durableCursor);assertEquals("",ledger.morning().ingestCursor())
        advance(31);syncState=MorningSyncState.COMPLETE;tick();assertTrue(ledger.morning().ingestCursor().isNotBlank())
    }
    @Test fun snapshotFailuresPersistBackoffBudgetAndManualRetrySameKey() {
        failRead=true;repeat(3) { tick();advance(121) };assertEquals(3,row().attempts);assertEquals(0,outbox().size)
        val calls=snapshotCalls;tick();assertEquals(calls,snapshotCalls);assertEquals("FAILED",row().state)
        failRead=false;ledger.morning().manualRetry(row().key,ledger.config().generation);tick()
        assertEquals("GENERATED_COMPLETE",row().state);assertEquals(1,row().revision)
    }
    @Test fun invalidReceiptIsPermanentAndCannotCreateOutbox() {
        invalid=true;tick();assertEquals(3,row().attempts);assertTrue(row().note.contains("INVALID"));assertTrue(outbox().isEmpty())
    }
    @Test fun disableDuringSnapshotFencesCommitAndOldGenerationCannotResume() {
        val old=ledger.config().generation
        beforeSnapshot={ ledger.setEnabled(false,env.now,env.zone);beforeSnapshot={} };tick()
        assertTrue(outbox().isEmpty());assertEquals("CANCELLED",row().state)
        ledger.setEnabled(true,env.now,env.zone);runBlocking { coordinator().tick(old) };assertTrue(outbox().isEmpty())
        tick();assertEquals("CANCELLED",row().state);assertTrue(outbox().none { it.reportKey==row().key })
    }
    @Test fun ownerSwitchAndCancellationDoNotCommitSuccess() {
        beforeSnapshot={ current=false };tick();assertTrue(outbox().isEmpty())
        current=true;beforeSnapshot={ throw CancellationException("synthetic stop") }
        assertThrows(CancellationException::class.java) { tick() };assertTrue(outbox().isEmpty())
    }
    @Test fun backlogIsOldestFirstBoundedAndContinuesWithoutLoss() {
        advance(30*86400);tick();assertEquals(day.plusDays(7),ledger.morning().cursor())
        repeat(4) { tick() };assertEquals(day.plusDays(31),ledger.morning().cursor())
        assertEquals(62,ledger.morning().reports().size);assertEquals(62,outbox().size)
        assertEquals(62,deliveryCalls);assertTrue(row().note.startsWith("DELAYED"))
    }
    @Test fun runtimeLeaseExcludesConcurrentConnectionAndRecoversAfterReboot() {
        val generation=ledger.config().generation;val token=ledger.morning().lease(generation,env)!!
        DailyReportLedger(context,profile,cipher).use { assertNull(it.morning().lease(generation,env)) }
        tick();assertTrue(outbox().isEmpty());env=env.copy(boot=2,elapsed=1,trigger="AFTER_REBOOT");tick()
        assertEquals(2,outbox().size);assertFalse(ledger.morning().valid(generation,token,env))
    }
    @Test fun crashAfterOutboxClaimBecomesUnknownAndRejectsStaleAck() {
        tick();val key=row().key;TextStoreTestSupport.open(context,DailyReportLedger.DATABASE).use { it.execSQL("UPDATE morning_outbox SET state='OUTBOX_READY',retry_ms=0,retry_elapsed=0") }
        val repository=ledger.morning();val generation=ledger.config().generation;val token=repository.lease(generation,env)!!
        val claimed=repository.claim(outbox().first(),generation,token,env)!!;repository.release(token)
        ledger.close();ledger=DailyReportLedger(context,profile,cipher);env=env.copy(boot=2,elapsed=1);tick()
        assertEquals(MorningDeliveryState.UNKNOWN,outbox().first().state)
        val lease=ledger.morning().lease(generation,env)!!
        ledger.morning().complete(claimed,MorningDeliveryResult(MorningDeliveryState.ACKNOWLEDGED,claimed.id,claimed.destination,claimed.bodyHash,"SYN-stale"),generation,lease,env)
        ledger.morning().release(lease);assertEquals(MorningDeliveryState.UNKNOWN,outbox().first().state)
        ledger.morning().manualRetry(key,generation);tick();assertEquals(MorningDeliveryState.UNKNOWN,outbox().first().state)
        ledger.morning().manualRetry(key,generation,true);tick();assertEquals(MorningDeliveryState.DRY_RUN_RECORDED,outbox().first().state)
    }
    @Test fun simulatedAckRequiresExactKeyDestinationHashAndAckedCannotRetry() {
        deliveryState=MorningDeliveryState.ACKNOWLEDGED;tick()
        assertTrue(outbox().all { it.state==MorningDeliveryState.ACKNOWLEDGED && it.receipt=="SYN-provider-ACK" })
        ledger.morning().manualRetry(row().key,ledger.config().generation,true);tick();assertEquals(2,deliveryCalls)
        assertTrue(outbox().all { it.state==MorningDeliveryState.ACKNOWLEDGED })
    }
    @Test fun wrongAckIsUnknownAndHasNoAutomaticRetry() {
        wrongAck=true;deliveryState=MorningDeliveryState.ACKNOWLEDGED;tick();advance(4000);tick()
        assertEquals(2,deliveryCalls);assertTrue(outbox().all { it.state==MorningDeliveryState.UNKNOWN && it.receipt.isEmpty() })
    }
    @Test fun knownRetryableRejectUsesSameIdentityElapsedBudgetAndManualReset() {
        deliveryState=MorningDeliveryState.RETRY_WAIT;tick();val ids=outbox().map { it.id };assertEquals(2,deliveryCalls)
        env=env.copy(now=env.now.plusSeconds(3600));tick();assertEquals(2,deliveryCalls) // same-boot elapsed protects forward wall jumps
        advance(31);tick();assertEquals(4,deliveryCalls);advance(61);tick();assertEquals(6,deliveryCalls)
        advance(300);tick();assertEquals(6,deliveryCalls);assertTrue(outbox().all { it.state==MorningDeliveryState.FAILED })
        assertEquals(ids,outbox().map { it.id });deliveryState=MorningDeliveryState.DRY_RUN_RECORDED
        ledger.morning().manualRetry(row().key,ledger.config().generation);tick();assertEquals(7,deliveryCalls)
    }
    @Test fun cancelDuringDeliveryPreservesUncertaintyAndNoAck() {
        beforeDelivery={ ledger.setEnabled(false,env.now,env.zone) };deliveryState=MorningDeliveryState.ACKNOWLEDGED;tick()
        assertEquals(MorningDeliveryState.UNKNOWN,outbox().first().state);assertFalse(outbox().any { it.state==MorningDeliveryState.ACKNOWLEDGED })
    }
    @Test fun permanentDeliveryFailureWaitsForManualRetry() {
        deliveryState=MorningDeliveryState.FAILED;tick();advance(4000);tick();assertEquals(2,deliveryCalls)
        assertTrue(outbox().all { it.state==MorningDeliveryState.FAILED })
    }
    @Test fun reportAndOutboxRollbackTogetherOnInsertFailure() {
        val repository=ledger.morning();val generation=ledger.config().generation
        val token=repository.lease(generation,env)!!;val row=repository.allocate(MorningReportWindow(day,MorningReportKind.DAILY),generation,token,env)
        TextStoreTestSupport.open(context,DailyReportLedger.DATABASE).use { it.execSQL("CREATE TRIGGER synthetic_outbox_failure BEFORE INSERT ON morning_outbox BEGIN SELECT RAISE(ABORT,'SYNTHETIC_FAIL'); END") }
        assertThrows(Exception::class.java) { repository.commit(row,snapshot(row.window),generation,token,env) }
        assertEquals(0,repository.get(row.key)!!.revision);assertTrue(repository.outbox().isEmpty())
        TextStoreTestSupport.open(context,DailyReportLedger.DATABASE).use { db->db.rawQuery("SELECT count(*) FROM morning_revisions",null).use { it.moveToFirst();assertEquals(0,it.getInt(0)) } }
        repository.release(token)
    }
    @Test fun cancelledCoroutineAfterClaimLeavesUnknownOnLeaseRecovery() {
        beforeDelivery={ throw CancellationException("synthetic process stop") }
        assertThrows(CancellationException::class.java) { tick() }
        assertEquals(MorningDeliveryState.IN_FLIGHT,outbox().first().state)
        beforeDelivery={};advance(121);tick();assertEquals(MorningDeliveryState.UNKNOWN,outbox().first().state)
    }
    @Test fun oldDateManualRefreshIsProcessedBeyondRollingRefreshHorizon() {
        tick();val key=row().key;advance(10*86400);tick();tick();contentRevision=2
        ledger.morning().manualRetry(key,ledger.config().generation);tick();assertEquals(2,ledger.morning().get(key)!!.revision)
    }
    @Test fun syncCheckpointRollbackCannotTurnAcceptedIntoComplete() {
        val repository=ledger.morning();val generation=ledger.config().generation;val token=repository.lease(generation,env)!!
        val request=repository.syncRequest("SYN-slot","MANUAL",env.now.minusSeconds(86400),env.now,generation,token,env)!!
        TextStoreTestSupport.open(context,DailyReportLedger.DATABASE).use { it.execSQL("CREATE TRIGGER synthetic_cursor_failure BEFORE UPDATE OF ingest_cursor ON morning_runtime BEGIN SELECT RAISE(ABORT,'SYNTHETIC_FAIL'); END") }
        assertThrows(Exception::class.java) { repository.syncComplete(request,MorningSyncResult(MorningSyncState.COMPLETE,"SYN-durable-cursor",true),generation,token,env) }
        assertEquals("",repository.ingestCursor());assertEquals("PENDING",repository.syncStatus());assertNull(repository.lastSuccessfulSync());repository.release(token)
    }
    @Test fun immutableHistoryAndOwnerAadRejectOverwriteOrOtherProfile() {
        tick();val row=row();val text=ledger.morning().body(row.key,1)
        TextStoreTestSupport.open(context,DailyReportLedger.DATABASE).use { db->
            assertThrows(Exception::class.java) { db.execSQL("UPDATE morning_revisions SET fingerprint='synthetic-rewrite'") }
            assertThrows(Exception::class.java) { db.execSQL("DELETE FROM morning_revisions") }
        }
        DailyReportLedger(context,"SYN-other-profile",cipher).use { other->assertThrows(Exception::class.java) { other.morning().body(row.key,1) } }
        assertEquals(text,ledger.morning().body(row.key,1))
    }
    @Test fun changedSourceBeforeDispatchCancelsOldOutboxWithoutAdapterCall() {
        beforeSnapshot={ if(snapshotCalls>=5)contentRevision=2 };tick()
        assertEquals(0,deliveryCalls);assertTrue(outbox().all { it.state==MorningDeliveryState.CANCELLED })
        beforeSnapshot={};tick();assertEquals(2,row().revision);assertEquals(2,deliveryCalls)
    }
    @Test fun backwardClockDoesNotDispatchAnAlreadyBuiltReportBeforeItsDueTime() {
        deliveryState=MorningDeliveryState.RETRY_WAIT;tick();assertEquals(2,deliveryCalls)
        env=env.copy(now=env.now.minusSeconds(3600),elapsed=env.elapsed+31_000);tick()
        assertEquals(2,deliveryCalls);assertTrue(outbox().all { it.state==MorningDeliveryState.RETRY_WAIT })
        env=env.copy(now=env.now.plusSeconds(3601));deliveryState=MorningDeliveryState.DRY_RUN_RECORDED;tick()
        assertEquals(4,deliveryCalls);assertEquals(2,outbox().size)
    }
    @Test fun repositoryRejectsEarlyCommitEvenIfItsCallerBypassesCoordinator() {
        env=env.copy(now=env.now.minusSeconds(1));val repository=ledger.morning();val generation=ledger.config().generation
        val token=repository.lease(generation,env)!!;val row=repository.allocate(MorningReportWindow(day,MorningReportKind.DAILY),generation,token,env)
        assertThrows(IllegalStateException::class.java) { repository.commit(row,snapshot(row.window),generation,token,env) }
        assertEquals(0,repository.get(row.key)!!.revision);assertTrue(outbox().isEmpty());repository.release(token)
    }
    @Test fun disablingScheduleAlsoCancelsDeferredSyncWithoutAdvancingItsCursor() {
        env=env.copy(online=false);tick();assertEquals("DEFERRED",ledger.morning().syncStatus())
        ledger.setEnabled(false,env.now,env.zone)
        assertEquals("CANCELLED",ledger.morning().syncStatus());assertEquals("",ledger.morning().ingestCursor())
    }
    @Test fun localDemo() {
        env=env.copy(online=false,doze=true,now=env.now.plusSeconds(3*3600),trigger="SYNTHETIC_DELAYED_DEMO");tick()
        assertEquals("GENERATED_PARTIAL",row().state);val old=row().revision
        advance(901);env=env.copy(online=true,doze=false);contentRevision=2;tick()
        assertEquals(old+1,row().revision);assertTrue(outbox().all { it.state==MorningDeliveryState.DRY_RUN_RECORDED })
        val output=java.io.File("build/reports/morning-demo.txt");output.parentFile!!.mkdirs()
        output.writeText("SYNTHETIC HOST DEMO · actual external sends=0 · mock sync only\n"+ledger.morning().reports().joinToString("\n") { "${it.window.reportDay} ${it.window.kind} r${it.revision} ${it.state} observed=${it.observed}" }+
            "\n"+outbox().joinToString("\n") { "r${it.revision} ${it.state}" }+"\n\n"+ledger.morning().body(row().key,row().revision))
    }
}
