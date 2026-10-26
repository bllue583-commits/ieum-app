package kr.callbrief

import android.content.Context
import kr.callbrief.kakao.core.DailyReport
import kr.callbrief.kakao.core.DeliveryCoordinator
import kr.callbrief.kakao.core.DeliveryResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DailyReportSchedulerTest : ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var ledger:DailyReportLedger
    private val profile="synthetic_profile_daily"
    private val target=LocalDate.parse("2026-10-10")
    private var env=ReportEnvironment(Instant.parse("2026-10-10T21:00:00Z"),ZoneId.of("Asia/Seoul"),elapsed=1000,boot=1)
    private var input=ready()
    private var currentScope=true
    private lateinit var coordinator:DailyReportCoordinator
    private val sender=StubSender()
    private val cipher=FixtureCipher()
    private class FixtureCipher:ReportPayloadCipher {
        private val key=SecretKeySpec(ByteArray(32) { 7 },"AES")
        override fun seal(text:String,aad:String,hasExistingPayload:Boolean):ByteArray {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(aad.toByteArray());return c.iv+c.doFinal(text.toByteArray())
        }
        override fun open(bytes:ByteArray,aad:String):String {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            c.updateAAD(aad.toByteArray());return String(c.doFinal(bytes.copyOfRange(12,bytes.size)))
        }
    }
    private class StubSender:DailyReportSender {
        var calls=0;var gateResult:DeliveryResult?=null;var result=DeliveryResult(DeliveryResult.Status.SENT)
        var before:()->Unit={}
        override fun gate()=gateResult
        override suspend fun send(report:DailyReport):DeliveryResult { check(report.finalized);calls++;before();return result }
    }
    private fun ready(revision:Long=1,policy:String="synthetic policy"):DailyReportInput {
        val text="합성: 검토된 업무 요약"
        return DailyReportInput(ReportReadiness.REVIEWED,listOf(ReviewedReportSource("SYN-CALL-1","a".repeat(64),1,
            1,DeliveryCoordinator.sha256(text),"SYN-ACCEPT-1",revision,1,1,text)),1,0,DeliveryCoordinator.sha256(policy))
    }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(DailyReportLedger.DATABASE);context.deleteDatabase("ieum-daily-reports-v1.db")
        ledger=DailyReportLedger(context,profile,cipher);ledger.setEnabled(true,env.now,env.zone)
        coordinator=DailyReportCoordinator(ledger,ReviewedDailySource { input },sender,{env},{currentScope})
    }
    @After fun cleanup() { ledger.close();context.deleteDatabase(DailyReportLedger.DATABASE);context.deleteDatabase("ieum-daily-reports-v1.db") }
    private fun row()=requireNotNull(ledger.get(ReportWindow.slot(profile,target)))
    private fun prepare() { runBlocking { coordinator.tick() };coordinator.review(row().id,input.basisHash,input.preview(),true) }
    private fun advance(seconds:Long) { env=env.copy(now=env.now.plusSeconds(seconds),elapsed=env.elapsed+seconds*1000) }

    private fun assertContinuousAcross(first:String,second:String) {
        val one=ledger.ensure(ReportWindow(target,ZoneId.of(first)),ledger.config().generation)
        ledger.observe(one.id,input,env.copy(now=one.window.due.plusSeconds(60)),ledger.config().generation)
        val two=ledger.ensure(ReportWindow(target.plusDays(1),ZoneId.of(second)),ledger.config().generation)
        assertEquals("UTC coverage must continue without a gap or overlap",one.window.until,two.window.from)
        val instant=Instant.parse("2026-10-10T20:00:00Z")
        assertEquals("one synthetic source must be covered exactly once",1,listOf(one,two).count {
            !instant.isBefore(it.window.from) && instant.isBefore(it.window.until)
        })
    }
    @Test fun reproV2LosAngelesToSeoulMustNotRepeatSyntheticSource()=assertContinuousAcross("America/Los_Angeles","Asia/Seoul")
    @Test fun reproV2SeoulToLosAngelesMustNotLoseSyntheticSource()=assertContinuousAcross("Asia/Seoul","America/Los_Angeles")
    private fun resumeAfterThirtyOneDays():SavedDailyReport {
        ledger.ensure(ReportWindow(target,env.zone),ledger.config().generation)
        ledger.setEnabled(false,env.now,env.zone);advance(31*86400L)
        ledger.setEnabled(true,env.now,env.zone);runBlocking { coordinator.tick() }
        return requireNotNull(ledger.get(ReportWindow.slot(profile,target.plusDays(31))))
    }
    @Test fun reproV3LongPauseHasSeparateBacklogReviewStateAndExactRange() {
        val backlog=resumeAfterThirtyOneDays()
        assertEquals(744L,java.time.Duration.between(backlog.window.from,backlog.window.until).toHours())
        assertTrue("multi-day coverage cannot be called yesterday's reviewed report",backlog.stateKo().contains("밀린 기록"))
        assertEquals(0,sender.calls)
    }
    @Test fun reproV3LongPauseCannotFreezeBacklogIntoDailySendQueue() {
        val backlog=resumeAfterThirtyOneDays()
        assertThrows(IllegalStateException::class.java) { coordinator.review(backlog.id,input.basisHash,input.preview(),true) }
        assertEquals(ReportDelivery.NOT_STORED,ledger.get(backlog.id)!!.delivery);assertEquals(0,sender.calls)
    }
    @Test fun reproV3EightHourTransitionStoresActualRangeInBody() {
        val first=ledger.ensure(ReportWindow(target,ZoneId.of("America/Los_Angeles")),ledger.config().generation)
        val next=ledger.ensure(ReportWindow(target.plusDays(1),ZoneId.of("Asia/Seoul")),ledger.config().generation)
        assertEquals(8L,java.time.Duration.between(next.window.from,next.window.until).toHours())
        ledger.freeze(next.id,input,input.basisHash,input.preview(),true,ledger.config().generation)
        assertTrue(ledger.text(next.id).contains(next.window.from.toString()))
        assertTrue(ledger.text(next.id).contains(next.window.until.toString()));assertEquals(first.window.until,next.window.from)
    }
    @Test fun oneDayPauseResumesAsAnOrdinarySingleDayReportAfterExplicitReview() {
        val first=ledger.ensure(ReportWindow(target,env.zone),ledger.config().generation)
        ledger.setEnabled(false,env.now,env.zone);advance(86400);ledger.setEnabled(true,env.now,env.zone)
        runBlocking { coordinator.tick() }
        val next=ledger.get(ReportWindow.slot(profile,target.plusDays(1)))!!
        assertEquals(first.window.until,next.window.from);assertEquals(24L,java.time.Duration.between(next.window.from,next.window.until).toHours())
        assertTrue(next.window.calendarDay);assertFalse(next.window.backlogReview)
        assertEquals(ReportReadiness.REVIEWED,next.readiness);assertEquals(0,sender.calls)
        coordinator.review(next.id,input.basisHash,input.preview(),true);runBlocking { coordinator.tick() }
        assertEquals(1,sender.calls);assertEquals(ReportDelivery.SENT,ledger.get(next.id)!!.delivery)
        assertEquals(ReportDelivery.CANCELLED,ledger.get(first.id)!!.delivery)
    }
    @Test fun thirtyOneDayBacklogPersistsExactRangeAndReviewStateAcrossRestartAndFutureAllocation() {
        val backlog=resumeAfterThirtyOneDays()
        assertEquals(ReportReadiness.BACKLOG_REVIEW,backlog.readiness)
        assertEquals(Instant.parse("2026-10-10T15:00:00Z"),backlog.window.from)
        assertEquals(Instant.parse("2026-11-10T15:00:00Z"),backlog.window.until)
        assertTrue(backlog.window.titleKo().contains("2026-10-11"));assertTrue(backlog.window.titleKo().contains("2026-11-11"))
        ledger.close();ledger=DailyReportLedger(context,profile,cipher)
        coordinator=DailyReportCoordinator(ledger,ReviewedDailySource { input },sender,{env},{currentScope})
        advance(86400);runBlocking { coordinator.tick();coordinator.tick() }
        val kept=ledger.get(backlog.id)!!;assertEquals(backlog.window,kept.window)
        assertEquals(ReportReadiness.BACKLOG_REVIEW,kept.readiness);assertEquals(ReportDelivery.NOT_STORED,kept.delivery)
        assertTrue(kept.note.contains("대상 1개"));assertEquals(0,sender.calls)
        val next=ledger.get(ReportWindow.slot(profile,target.plusDays(32)))!!
        assertEquals(backlog.window.until,next.window.from);assertTrue(next.window.calendarDay)
    }
    @Test fun unresolvedBacklogRemainsVisibleAfterSixtyNewerReports() {
        val backlog=resumeAfterThirtyOneDays()
        repeat(65) { n ->ledger.allocate(ReportWindow(backlog.window.date.plusDays(n+2L),env.zone),ledger.config().generation) }
        assertTrue(ledger.list().any { it.id==backlog.id });assertTrue(ledger.list().size>60)
        assertEquals(ReportReadiness.BACKLOG_REVIEW,ledger.get(backlog.id)!!.readiness)
        assertEquals(ReportDelivery.NOT_STORED,ledger.get(backlog.id)!!.delivery)
    }
    @Test fun fortyHourTimezoneTransitionIsBacklogReviewAndPreservesBodyAndMetadataRange() {
        val first=ledger.ensure(ReportWindow(target,ZoneId.of("Asia/Seoul")),ledger.config().generation)
        val next=ledger.ensure(ReportWindow(target.plusDays(1),ZoneId.of("America/Los_Angeles")),ledger.config().generation)
        env=env.copy(now=next.window.due.plusSeconds(60),zone=next.window.zone)
        runBlocking { coordinator.tick() }
        assertEquals(40L,java.time.Duration.between(next.window.from,next.window.until).toHours())
        assertEquals(first.window.until,next.window.from);assertTrue(next.window.backlogReview)
        assertEquals(ReportReadiness.BACKLOG_REVIEW,ledger.get(next.id)!!.readiness)
        val body=next.window.bodyWithRange(input.preview())
        assertTrue(body.contains(next.window.from.toString()));assertTrue(body.contains(next.window.until.toString()))
        assertTrue(body.contains("밀린 기록 검토"));assertTrue(body.contains("자동 발송 제외"))
        assertThrows(IllegalStateException::class.java) { coordinator.review(next.id,input.basisHash,input.preview(),true) }
        assertEquals(0,sender.calls);assertEquals(ReportDelivery.NOT_STORED,ledger.get(next.id)!!.delivery)
    }
    @Test fun preV3QueuedBacklogCannotClaimOrSendAndItsFrozenCopyIsPreserved() {
        val backlog=resumeAfterThirtyOneDays();val oldText="PUBLIC_SYNTHETIC_PRE_V3_BACKLOG_BODY"
        val aad="$profile\n${backlog.id}\n${backlog.window.date}\n${backlog.window.zone.id}\n${backlog.window.from}\n${backlog.window.until}"
        val payload=cipher.seal(oldText,aad,false);val hash=DeliveryCoordinator.sha256(oldText)
        TextStoreTestSupport.open(context,DailyReportLedger.DATABASE).use { db ->
            db.execSQL("UPDATE reports SET readiness='REVIEWED',delivery='SEND_WAITING',basis=?,payload_hash=?,payload=? WHERE id=?",
                arrayOf(input.basisHash,hash,payload,backlog.id))
        }
        assertNull(ledger.claim(backlog.id,input,env,ledger.config().generation))
        runBlocking { coordinator.tick();coordinator.tick() }
        val kept=ledger.get(backlog.id)!!;assertEquals(ReportReadiness.BACKLOG_REVIEW,kept.readiness)
        assertEquals(0,kept.attempts);assertEquals(0,sender.calls);assertEquals(hash,kept.payloadHash)
        assertEquals(oldText,ledger.text(kept.id));assertEquals(ReportDelivery.SEND_WAITING,kept.delivery)
        assertTrue(kept.window.bodyWithRange(oldText).contains(kept.window.from.toString()))
    }
    @Test fun normalTwentyFiveHourDstCivilDayIsNotMultidayCatchup() {
        val window=ReportWindow(LocalDate.parse("2026-11-01"),ZoneId.of("America/New_York"))
        assertEquals(25L,java.time.Duration.between(window.from,window.until).toHours())
        assertTrue(window.calendarDay);assertFalse(window.backlogReview)
        assertFalse(window.titleKo().contains("밀린 기록"))
    }
    @Test fun actualRangeHeaderCountsTowardMessageLimitWithoutSilentlyTruncatingSummary() {
        ledger.ensure(ReportWindow(target,ZoneId.of("America/Los_Angeles")),ledger.config().generation)
        val next=ledger.ensure(ReportWindow(target.plusDays(1),ZoneId.of("Asia/Seoul")),ledger.config().generation)
        assertThrows(IllegalArgumentException::class.java) { ledger.freeze(next.id,input,input.basisHash,"a".repeat(16000),true,ledger.config().generation) }
        assertEquals(ReportDelivery.NOT_STORED,ledger.get(next.id)!!.delivery);assertTrue(ledger.get(next.id)!!.basisHash.isBlank())
    }

    @Test fun deviceTimezone06TargetUsesCalendarAndDst() {
        val zone=ZoneId.of("America/New_York")
        val before=ReportWindow(LocalDate.parse("2026-03-06"),zone).due
        val after=ReportWindow(LocalDate.parse("2026-03-07"),zone).due
        assertEquals(23*3600L,java.time.Duration.between(before,after).seconds)
        assertEquals(6,after.atZone(zone).hour)
        assertEquals(Instant.parse("2026-10-11T13:00:00Z"),ReportWindow(target,ZoneId.of("America/Los_Angeles")).due)
    }
    @Test fun before06DoesNotObserveOrProduceCompletedReport() {
        env=env.copy(now=env.now.minusSeconds(60))
        runBlocking { coordinator.tick() }
        assertNull(row().observed);assertEquals(ReportReadiness.REVIEWED,row().readiness)
        assertEquals(ReportDelivery.NOT_STORED,row().delivery);assertEquals(0,sender.calls)
    }
    @Test fun nextTargetIsTomorrowAt06AfterCurrentTarget() {
        val next=ReportWindow.next(env.now,env.zone)
        assertEquals(target.plusDays(1),next.date);assertEquals(6,next.due.atZone(env.zone).hour)
    }
    @Test fun actualWorkRequestFreezesProfileGenerationAndTargetsDevice06WithoutNetworkGate() {
        val now=Instant.parse("2026-10-11T12:00:00Z") // 05:00 in Los Angeles.
        val request=DailyReportSchedule.request(now,ZoneId.of("America/Los_Angeles"),DailyReportRuntime.Scope(context,profile,7),9,"DAILY_06_TARGET")
        assertEquals(3600_000L,request.workSpec.initialDelay)
        assertEquals(profile,request.workSpec.input.getString("profile"))
        assertEquals(7L,request.workSpec.input.getLong("account_epoch",-1))
        assertEquals(9L,request.workSpec.input.getLong("generation",-1))
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED,request.workSpec.constraints.requiredNetworkType)
    }
    @Test fun delayedExecutionPersistsObservedTimeAndHonestReason() {
        advance(11700);env=env.copy(trigger="AFTER_REBOOT")
        runBlocking { coordinator.tick() }
        assertEquals(11700,java.time.Duration.between(row().window.due,row().observed).seconds)
        assertTrue(row().note.contains("지연"));assertTrue(row().note.contains("AFTER_REBOOT"));assertEquals(0,sender.calls)
    }
    @Test fun originalWindowStaysFrozenAcrossTimezoneChangeAfterObservation() {
        prepare();val old=row().window
        env=env.copy(zone=ZoneId.of("America/Los_Angeles"),now=env.now.plusSeconds(86400))
        runBlocking { coordinator.tick() }
        assertEquals(old,row().window);assertEquals(1,sender.calls)
        runBlocking { coordinator.tick() };assertEquals(1,sender.calls)
    }
    @Test fun allocatedFutureWindowRetainsItsUtcCoverageAndZone() {
        val old=ledger.ensure(ReportWindow(target.plusDays(1),ZoneId.of("Asia/Seoul")),ledger.config().generation)
        val moved=ledger.ensure(ReportWindow(target.plusDays(1),ZoneId.of("Europe/London")),ledger.config().generation)
        assertEquals(old.id,moved.id);assertEquals(old.window,moved.window)
    }
    @Test fun duplicateEventsAndTwoLedgerConnectionsKeepOneReportSlot() {
        prepare();val second=DailyReportLedger(context,profile,cipher)
        try {
            repeat(10) { second.ensure(ReportWindow(target,ZoneId.of("UTC")),second.config().generation) }
            runBlocking { coordinator.tick();coordinator.tick() }
            assertEquals(1,ledger.list().count { it.window.date==target });assertEquals(1,sender.calls)
        } finally { second.close() }
    }
    @Test fun pendingAnalysisCannotBecomeReportEvenIfSomeSourceTextExists() {
        input=ready().copy(readiness=ReportReadiness.WAITING_ANALYSIS,expectedCount=2,pendingCount=1)
        runBlocking { coordinator.tick() }
        assertEquals(ReportReadiness.WAITING_ANALYSIS,row().readiness)
        assertThrows(IllegalStateException::class.java) { coordinator.review(row().id,input.basisHash,"합성: 일부 결과",true) }
        assertEquals(ReportDelivery.NOT_STORED,row().delivery);assertEquals(0,sender.calls)
    }
    @Test fun readOnlyProposalAndUnreviewedResultAreNotFinalInput() {
        input=DailyReportInput(ReportReadiness.WAITING_REVIEW,emptyList(),1,1,DeliveryCoordinator.sha256("synthetic policy"))
        runBlocking { coordinator.tick() };assertFalse(input.fullyReviewed)
        assertEquals(ReportReadiness.WAITING_REVIEW,row().readiness);assertEquals(0,sender.calls)
    }
    @Test fun missingReviewReceiptOrWrongSummaryHashCannotPassContract() {
        val original=ready();val source=original.sources.single()
        assertFalse(original.copy(sources=listOf(source.copy(reviewReceipt=""))).fullyReviewed)
        assertFalse(original.copy(sources=listOf(source.copy(summaryHash="b".repeat(64)))).fullyReviewed)
        assertFalse(original.copy(sources=listOf(source,source),expectedCount=2).fullyReviewed)
    }
    @Test fun explicitReportPrivacyReviewIsRequired() {
        runBlocking { coordinator.tick() }
        assertThrows(IllegalStateException::class.java) { coordinator.review(row().id,input.basisHash,input.preview(),false) }
        assertEquals(ReportDelivery.NOT_STORED,row().delivery)
    }
    @Test fun encryptedAppCopyIsCreatedBeforeSenderAndPersistsOnReopen() {
        prepare();assertEquals(ReportDelivery.SEND_WAITING,row().delivery);assertEquals(0,sender.calls)
        val text=ledger.text(row().id);assertEquals(input.preview(),text)
        ledger.close();ledger=DailyReportLedger(context,profile,cipher)
        assertEquals(ReportDelivery.SEND_WAITING,row().delivery);assertEquals(text,ledger.text(row().id))
        TextStoreTestSupport.open(context,DailyReportLedger.DATABASE).use { db ->
            db.rawQuery("SELECT payload FROM reports WHERE id=?",arrayOf(row().id)).use { c -> c.moveToFirst();assertFalse(String(c.getBlob(0)).contains(text)) }
        }
    }
    @Test fun payloadCannotBeOverwrittenAfterReview() {
        prepare();val original=ledger.text(row().id)
        assertThrows(IllegalStateException::class.java) { coordinator.review(row().id,input.basisHash,"합성: 다른 보고",true) }
        assertEquals(original,ledger.text(row().id))
    }
    @Test fun utcCoverageAndEncryptedPayloadPersistAcrossReopeningLedger() {
        prepare();val first=row();val copy=ledger.text(first.id)
        val next=ledger.ensure(ReportWindow(target.plusDays(1),ZoneId.of("America/Los_Angeles")),ledger.config().generation)
        ledger.close();ledger=DailyReportLedger(context,profile,cipher)
        assertEquals(first.window,ledger.get(first.id)!!.window);assertEquals(copy,ledger.text(first.id))
        assertEquals(first.window.until,ledger.get(next.id)!!.window.from)
    }
    @Test fun nonAdvancingExtremeTimezoneBoundaryIsSkippedWithoutCreatingEmptyCoverage() {
        val first=ledger.ensure(ReportWindow(target,ZoneId.of("Etc/GMT+12")),ledger.config().generation)
        assertNull(ledger.allocate(ReportWindow(target.plusDays(1),ZoneId.of("Pacific/Kiritimati")),ledger.config().generation))
        val next=ledger.ensure(ReportWindow(target.plusDays(2),ZoneId.of("Pacific/Kiritimati")),ledger.config().generation)
        assertEquals(first.window.until,next.window.from);assertTrue(next.window.until.isAfter(next.window.from))
    }
    @Test fun cancelledSlotDoesNotGetReassignedCoverageOrResurrectedAfterEnable() {
        prepare();val before=row();ledger.setEnabled(false,env.now,env.zone)
        ledger.setEnabled(true,env.now.plusSeconds(86400),ZoneId.of("America/Los_Angeles"))
        val same=ledger.ensure(ReportWindow(target,ZoneId.of("America/Los_Angeles")),ledger.config().generation)
        assertEquals(before.window,same.window);assertEquals(ReportDelivery.CANCELLED,same.delivery)
        assertEquals(before.payloadHash,same.payloadHash)
    }
    @Test fun existingV1ReportsArePreservedAndBlockAutomaticOptIn() {
        val legacy=context.getDatabasePath("ieum-daily-reports-v1.db")
        context.openOrCreateDatabase("ieum-daily-reports-v1.db",0,null).use { db ->
            db.execSQL("CREATE TABLE reports(id TEXT,payload BLOB)")
            db.execSQL("INSERT INTO reports VALUES('SYN-LEGACY',X'01020304')")
        }
        val before=legacy.readBytes();ledger.close();ledger=DailyReportLedger(context,profile,cipher)
        assertTrue(ledger.config().legacyHold);assertFalse(ledger.config().enabled)
        assertThrows(IllegalStateException::class.java) { ledger.setEnabled(true,env.now,env.zone) }
        assertArrayEquals(before,legacy.readBytes())
    }
    @Test fun leaseExpiryRequiresSubsequentReconciliationAndCannotConfirmLateCallback() {
        prepare();val generation=ledger.config().generation;val id=row().id
        val claim=ledger.claim(id,input,env,generation)!!;advance(121)
        assertEquals(ReportDelivery.SENDING,row().delivery) // Wall/elapsed advancement alone runs no timer.
        ledger.recover(env);assertEquals(ReportDelivery.UNKNOWN,row().delivery)
        ledger.complete(id,claim,DeliveryResult(DeliveryResult.Status.SENT),env,generation)
        assertEquals(ReportDelivery.UNKNOWN,row().delivery);assertEquals(0,sender.calls)
    }
    @Test fun wrongEncryptionAadRejectsAnotherProfile() {
        val encrypted=cipher.seal("합성: private","SYN-profile-A",false)
        assertThrows(Exception::class.java) { cipher.open(encrypted,"SYN-profile-B") }
    }
    @Test fun offlineDoesNotConsumeAttemptAndPreservesSavedReport() {
        prepare();env=env.copy(online=false)
        repeat(4) { runBlocking { coordinator.tick() } }
        assertEquals(0,row().attempts);assertEquals(0,sender.calls);assertTrue(row().note.contains("네트워크"))
        env=env.copy(online=true);runBlocking { coordinator.tick() };assertEquals(ReportDelivery.SENT,row().delivery)
    }
    @Test fun dozeAndBatteryConditionsWaitWithoutFailingOrSending() {
        prepare();env=env.copy(doze=true)
        runBlocking { coordinator.tick() };assertEquals(0,row().attempts);assertTrue(row().note.contains("절전"))
        env=env.copy(doze=false,batteryOkay=false);runBlocking { coordinator.tick() }
        assertEquals(0,sender.calls);assertEquals(ReportDelivery.SEND_WAITING,row().delivery)
    }
    @Test fun disabledLiveSenderDoesNotCallSdkOrConsumeAttempts() {
        prepare();sender.gateResult=DeliveryResult(DeliveryResult.Status.DISABLED)
        runBlocking { coordinator.tick() };assertEquals(0,row().attempts);assertEquals(0,sender.calls)
        assertTrue(row().note.contains("비활성"));assertFalse(BuildConfig.DAILY_REPORT_KAKAO_LIVE_ENABLED)
        assertEquals(DeliveryResult.Status.DISABLED,KakaoDailyReportSender(context).gate()?.status)
    }
    @Test fun sourceRevisionOrExclusionPolicyChangeBlocksQueuedReport() {
        prepare();input=ready(revision=2,policy="changed exclusions")
        runBlocking { coordinator.tick() }
        assertEquals(ReportReadiness.STALE_REVIEW,row().readiness);assertEquals(0,sender.calls)
        assertEquals(0,row().attempts);assertEquals("합성: 검토된 업무 요약",ledger.text(row().id))
    }
    @Test fun generationCancellationSurvivesReenableAndDoesNotResurrectQueuedReport() {
        prepare();ledger.setEnabled(false,env.now,env.zone);ledger.setEnabled(true,env.now,env.zone)
        runBlocking { coordinator.tick() };assertEquals(ReportDelivery.CANCELLED,row().delivery);assertEquals(0,sender.calls)
    }
    @Test fun accountScopeChangeBlocksBeforeNetwork() {
        prepare();currentScope=false
        runBlocking { coordinator.tick() };assertEquals(0,sender.calls);assertEquals(0,row().attempts)
    }
    @Test fun duplicateClaimHasNoSecondSenderEffect() {
        prepare();val generation=ledger.config().generation
        val first=ledger.claim(row().id,input,env,generation);assertNotNull(first)
        assertNull(ledger.claim(row().id,input,env,generation));runBlocking { coordinator.tick() };assertEquals(0,sender.calls)
    }
    @Test fun processDeathBeforeCallbackBecomesUnknownAfterLeaseAndNeverRetries() {
        prepare();ledger.claim(row().id,input,env,ledger.config().generation)
        ledger.close();ledger=DailyReportLedger(context,profile,cipher)
        coordinator=DailyReportCoordinator(ledger,ReviewedDailySource { input },sender,{env})
        advance(121);runBlocking { coordinator.tick() }
        assertEquals(ReportDelivery.UNKNOWN,row().delivery);assertEquals(0,sender.calls)
    }
    @Test fun rebootInvalidatesOldElapsedClaimAndNeverBlindlyResends() {
        prepare();ledger.claim(row().id,input,env,ledger.config().generation)
        env=env.copy(boot=2,elapsed=1,trigger="AFTER_REBOOT")
        runBlocking { coordinator.tick() };assertEquals(ReportDelivery.UNKNOWN,row().delivery);assertEquals(0,sender.calls)
    }
    @Test fun ambiguousCallbackAndModuleInFlightAreTerminalUnknown() {
        prepare();sender.result=DeliveryResult(DeliveryResult.Status.UNKNOWN)
        runBlocking { coordinator.tick();coordinator.tick() }
        assertEquals(ReportDelivery.UNKNOWN,row().delivery);assertEquals(1,sender.calls)
    }
    @Test fun syntheticContractSuccessDoesNotBecomeActualSent() {
        prepare();sender.result=DeliveryResult(DeliveryResult.Status.TEST_CONTRACT_PASSED)
        runBlocking { coordinator.tick();coordinator.tick() }
        assertEquals(ReportDelivery.LOCAL_STORED,row().delivery);assertNotEquals(ReportDelivery.SENT,row().delivery)
        assertEquals(1,sender.calls)
    }
    @Test fun quotaRetryUsesModuleNotBeforeAndSameFrozenDateZoneAndPayload() {
        prepare();val retry=env.now.plusSeconds(3600)
        sender.result=DeliveryResult(DeliveryResult.Status.RATE_LIMITED,retry)
        runBlocking { coordinator.tick();coordinator.tick() };assertEquals(1,sender.calls);assertEquals(retry,row().retryAt)
        advance(3601);sender.result=DeliveryResult(DeliveryResult.Status.SENT);runBlocking { coordinator.tick() }
        assertEquals(2,sender.calls);assertEquals(ReportDelivery.SENT,row().delivery);assertEquals(target,row().window.date)
    }
    @Test fun retryBudgetIsPersistentAndDuplicateEventsCannotResetIt() {
        prepare();sender.result=DeliveryResult(DeliveryResult.Status.CONNECTION_CHECK_FAILED)
        repeat(3) { runBlocking { coordinator.tick() };advance(121) }
        runBlocking { coordinator.tick() };assertEquals(3,sender.calls);assertEquals(3,row().attempts)
        assertEquals(ReportDelivery.FAILED,row().delivery)
    }
    @Test fun permanentFailureDoesNotRetry() {
        prepare();sender.result=DeliveryResult(DeliveryResult.Status.FAILED)
        runBlocking { coordinator.tick() };advance(3600);runBlocking { coordinator.tick() }
        assertEquals(1,sender.calls);assertEquals(ReportDelivery.FAILED,row().delivery)
    }
    @Test fun withdrawalAfterRemoteAcceptanceDoesNotHideAmbiguousState() {
        prepare();sender.before={ ledger.setEnabled(false,env.now,env.zone) }
        runBlocking { coordinator.tick() };assertEquals(1,sender.calls)
        assertEquals(ReportDelivery.UNKNOWN,row().delivery);assertFalse(ledger.config().enabled)
    }
    @Test fun lateRevisionAfterSentIsVisibleAndCannotResend() {
        prepare();runBlocking { coordinator.tick() };input=ready(revision=2)
        runBlocking { coordinator.tick() };assertEquals(1,sender.calls);assertEquals(ReportReadiness.STALE_REVIEW,row().readiness)
        assertEquals(ReportDelivery.SENT,row().delivery);assertTrue(row().note.contains("변경"))
        assertTrue(row().stateKo().contains("최신 변경은 검토 필요"))
    }
    @Test fun backwardClockDoesNotRepeatTheSameDailySlot() {
        prepare();runBlocking { coordinator.tick() };env=env.copy(now=env.now.minusSeconds(3600))
        runBlocking { coordinator.tick() };env=env.copy(now=env.now.plusSeconds(3600));runBlocking { coordinator.tick() }
        assertEquals(1,sender.calls);assertEquals(1,ledger.list().count { it.window.date==target })
    }
    @Test fun emptyDayDoesNotGenerateCompletedOrSentReport() {
        input=DailyReportInput(ReportReadiness.NO_RECORDINGS,emptyList(),0,0,DeliveryCoordinator.sha256("synthetic policy"))
        runBlocking { coordinator.tick() };assertEquals(ReportReadiness.NO_RECORDINGS,row().readiness)
        assertEquals(ReportDelivery.NOT_STORED,row().delivery);assertEquals(0,sender.calls)
    }
    @Test fun missingCipherKeyDoesNotClearSavedPayloadOrReportMetadata() {
        prepare();val id=row().id;ledger.close()
        ledger=DailyReportLedger(context,profile,object:ReportPayloadCipher {
            override fun seal(text:String,aad:String,hasExistingPayload:Boolean):ByteArray=error("MISSING_KEY")
            override fun open(bytes:ByteArray,aad:String):String=error("MISSING_KEY")
        })
        assertThrows(IllegalStateException::class.java) { ledger.text(id) };assertEquals(ReportDelivery.SEND_WAITING,row().delivery)
    }
    @Test fun unresolvedInputCannotPretendAllExpectedFilesWereReviewed() {
        assertFalse(ready().copy(expectedCount=2).fullyReviewed)
        assertFalse(ready().copy(pendingCount=1).fullyReviewed)
        assertFalse(ready().copy(sources=emptyList()).fullyReviewed)
    }
    @Test fun changedThenRestoredPolicyDoesNotResurrectPreviouslyStaleQueuedReport() {
        prepare();val original=input;input=ready(policy="changed")
        runBlocking { coordinator.tick() };input=original;runBlocking { coordinator.tick() }
        assertEquals(ReportReadiness.STALE_REVIEW,row().readiness);assertEquals(0,sender.calls)
    }
    @Test fun transientSourceReadFailureWaitsAndCanResumeWithSameVerifiedInput() {
        prepare();input=DailyReportInput(ReportReadiness.SOURCE_FAILURE,emptyList(),0,1,DeliveryCoordinator.sha256("source read failed"))
        runBlocking { coordinator.tick() };assertEquals(0,sender.calls);assertEquals(ReportReadiness.SOURCE_FAILURE,row().readiness)
        input=ready();runBlocking { coordinator.tick() };assertEquals(ReportDelivery.SENT,row().delivery)
    }
    @Test fun reviewedAppCopyPreparedBefore06WaitsUntilTarget() {
        env=env.copy(now=env.now.minusSeconds(3600));prepare()
        assertNull(row().observed);runBlocking { coordinator.tick() };assertEquals(0,sender.calls)
        advance(3600);runBlocking { coordinator.tick() };assertEquals(1,sender.calls);assertNotNull(row().observed)
    }
    @Test fun retryFailureReasonSurvivesLaterLocalReconciliation() {
        prepare();sender.result=DeliveryResult(DeliveryResult.Status.RATE_LIMITED,env.now.plusSeconds(3600))
        runBlocking { coordinator.tick() };val note=row().note
        runBlocking { coordinator.tick() };assertEquals(note,row().note);assertTrue(row().note.contains("한도"))
    }
    @Test fun longRebootBacklogIsBoundedAndCursorContinuesNextRun() {
        advance(30*86400);coordinator.reconcile()
        assertEquals(14,ledger.list().size)
        assertEquals(target.plusDays(14),ledger.config().cursor)
        coordinator.reconcile();assertEquals(28,ledger.list().size)
    }
    @Test fun invalidUnicodeCannotBeStoredOrBecomeUnknownDelivery() {
        runBlocking { coordinator.tick() }
        assertThrows(IllegalArgumentException::class.java) { coordinator.review(row().id,input.basisHash,"합성\uD800",true) }
        assertEquals(ReportDelivery.NOT_STORED,row().delivery);assertEquals(0,sender.calls)
    }
}
