package kr.callbrief

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kr.callbrief.core.PrivacyRules
import kr.callbrief.kakao.core.DeliveryCoordinator
import org.json.JSONArray
import org.json.JSONObject
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

/** Synthetic, code13-shaped canonical metadata; no real file/provider/auth/network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DailyReportCanonicalInputTest : ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var db:TextStoreTestSupport.TestDatabase
    private val window=ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul"))
    private val text="합성: 사용자가 검토한 업무 변경"
    private var policy=JSONObject()
    private fun read()=CanonicalReviewedDailySource(context) { policy }.snapshot(window)
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(GeneralLocalStore.DATABASE)
        db=TextStoreTestSupport.TestDatabase(kr.co.callsummary.core.android.ProtectedDatabase.open(context,GeneralLocalStore.DATABASE,"CREATE TABLE synthetic_fixture_seed(id INTEGER PRIMARY KEY)"))
        db.execSQL("CREATE TABLE board_state(owner_key TEXT,revision INTEGER)")
        db.execSQL("CREATE TABLE folder_grants(grant_id TEXT,active INTEGER)")
        db.execSQL("CREATE TABLE tasks(task_id TEXT,task_revision INTEGER,status TEXT,case_id TEXT)")
        db.execSQL("CREATE TABLE recordings(recording_id TEXT,owner_key TEXT,grant_id TEXT,source_version INTEGER,effective_summary_version INTEGER,"+
            "original_started_at_ms INTEGER,display_name TEXT,byte_count INTEGER,mime_type TEXT,modified_at_ms INTEGER,presence TEXT,head_revision INTEGER,"+
            "user_revision INTEGER,lock_revision INTEGER,person_id TEXT,company_id TEXT,manual_status TEXT,manual_text TEXT,task_id TEXT)")
        db.execSQL("CREATE TABLE source_versions(recording_id TEXT,source_version INTEGER,audio_sha256 TEXT)")
        db.execSQL("CREATE TABLE processing(recording_id TEXT,source_version INTEGER,state TEXT)")
        db.execSQL("CREATE TABLE local_files(recording_id TEXT,actual_uri TEXT)")
        db.execSQL("CREATE TABLE local_visibility(recording_id TEXT,archived INTEGER)")
        db.execSQL("CREATE TABLE canonical_storage(audio_sha256 TEXT,available INTEGER)")
        db.execSQL("CREATE TABLE summary_versions(recording_id TEXT,summary_version INTEGER,source_version INTEGER,audio_sha256 TEXT,summary_text TEXT,summary_sha256 TEXT)")
        db.execSQL("CREATE TABLE receipts(event_id TEXT,operation TEXT,recording_id TEXT,summary_version INTEGER,outcome TEXT,created_at_ms INTEGER)")
        db.execSQL("INSERT INTO board_state VALUES(?,1)",arrayOf(GeneralLocalStore.OWNER))
        db.execSQL("INSERT INTO folder_grants VALUES('SYN-GRANT',1)")
        add("1")
    }
    private fun add(id:String) {
        val started=Instant.parse("2026-10-10T02:00:00Z").toEpochMilli()
        db.execSQL("INSERT INTO tasks VALUES(?,1,'open',?)",arrayOf("SYN-TASK-$id","SYN-CASE-$id"))
        db.execSQL("INSERT INTO recordings VALUES(?,?,'SYN-GRANT',1,1,?,'통화 녹음 합성업무_01000000001_20261010_110000.wav',100,'audio/wav',?,'PRESENT',1,1,1,?,?,NULL,NULL,?)",
            arrayOf("SYN-CALL-$id",GeneralLocalStore.OWNER,started,started+60_000,"SYN-PERSON-$id","SYN-COMPANY-$id","SYN-TASK-$id"))
        db.execSQL("INSERT INTO source_versions VALUES(?,1,?)",arrayOf("SYN-CALL-$id","a".repeat(64)))
        db.execSQL("INSERT INTO processing VALUES(?,1,'REVIEW')",arrayOf("SYN-CALL-$id"))
        db.execSQL("INSERT INTO local_files VALUES(?,?)",arrayOf("SYN-CALL-$id","content://synthetic/call-$id"))
        db.execSQL("INSERT INTO local_visibility VALUES(?,0)",arrayOf("SYN-CALL-$id"))
        db.execSQL("INSERT INTO canonical_storage VALUES(?,1)",arrayOf("a".repeat(64)))
        db.execSQL("INSERT INTO summary_versions VALUES(?,1,1,?,?,?)",arrayOf("SYN-CALL-$id","a".repeat(64),text,DeliveryCoordinator.sha256(text)))
        db.execSQL("INSERT INTO receipts VALUES(?,'ACCEPT',?,1,'SUMMARY_ACCEPTED_BY_USER',1)",arrayOf("SYN-ACCEPT-$id","SYN-CALL-$id"))
    }
    @After fun cleanup() { db.close();context.deleteDatabase(GeneralLocalStore.DATABASE) }
    @Test fun morningAdapterUsesOriginalKstOccurrenceHalfOpenDailyAndWeeklyWindows() {
        val day=LocalDate.parse("2026-10-11")
        val port=CanonicalMorningSnapshotPort(CanonicalReviewedDailySource(context) { policy })
        add("2");add("3");add("4")
        val times=listOf("2026-10-09T15:00:00Z","2026-10-10T14:59:59.999Z","2026-10-10T15:00:00Z","2026-10-03T15:00:00Z")
        times.forEachIndexed { i,at->db.execSQL("UPDATE recordings SET original_started_at_ms=? WHERE recording_id=?",arrayOf(Instant.parse(at).toEpochMilli(),"SYN-CALL-${i+1}")) }
        val daily=port.snapshot(MorningReportWindow(day,MorningReportKind.DAILY)).validated()
        val weekly=port.snapshot(MorningReportWindow(day,MorningReportKind.ROLLING_7_DAY)).validated()
        assertEquals(setOf("SYN-CALL-1","SYN-CALL-2"),daily.input.sources.map { it.id }.toSet())
        assertEquals(setOf("SYN-CALL-1","SYN-CALL-2","SYN-CALL-4"),weekly.input.sources.map { it.id }.toSet())
        assertEquals(MorningCoverage.PARTIAL,weekly.coverage) // no SMS/Naver comprehensive claim
    }
    @Test fun morningAdapterPreservesDoneArchiveAndUnknownOccurrenceInsteadOfInventingDates() {
        val port=CanonicalMorningSnapshotPort(CanonicalReviewedDailySource(context) { policy })
        val w=MorningReportWindow(LocalDate.parse("2026-10-11"),MorningReportKind.ROLLING_7_DAY)
        db.execSQL("UPDATE tasks SET status='done',task_revision=2")
        val done=port.snapshot(w);assertTrue(done.input.preview().contains("완료"))
        db.execSQL("UPDATE recordings SET original_started_at_ms=NULL")
        assertEquals(1,port.snapshot(w).unknownTimeCount);assertEquals(1,port.snapshot(w).input.pendingCount)
        db.execSQL("UPDATE local_visibility SET archived=1")
        val hidden=port.snapshot(w);assertEquals(0,hidden.input.expectedCount);assertTrue(hidden.input.sources.isEmpty())
        assertEquals(MorningCoverage.PARTIAL,hidden.coverage)
    }
    @Test fun reproV2IncompleteAliasCannotDisappearFromExpectedInventory() {
        add("2");db.execSQL("DELETE FROM local_files WHERE recording_id='SYN-CALL-2'")
        val input=read()
        assertEquals("canonical inventory includes incomplete alias",2,input.expectedCount)
        assertEquals(1,input.pendingCount);assertFalse(input.fullyReviewed)
        assertEquals(ReportReadiness.WAITING_ANALYSIS,input.readiness)
    }
    private fun incomplete(sql:String,expectedPending:Int=1) {
        add("2");db.execSQL(sql);val input=read()
        assertEquals(2,input.expectedCount);assertEquals(expectedPending,input.pendingCount)
        assertFalse(input.fullyReviewed);assertEquals(ReportReadiness.WAITING_ANALYSIS,input.readiness)
    }
    @Test fun missingCurrentSourceVersionCannotDisappear()=incomplete("DELETE FROM source_versions WHERE recording_id='SYN-CALL-2'")
    @Test fun missingCurrentProcessingCannotDisappear()=incomplete("DELETE FROM processing WHERE recording_id='SYN-CALL-2'")
    @Test fun missingGrantCannotBeTreatedAsConfirmedRevocation()=incomplete("DELETE FROM folder_grants",2)
    @Test fun missingBoardRevisionCannotDisappear()=incomplete("DELETE FROM board_state",2)
    @Test fun missingVisibilityCannotBeTreatedAsKnownArchive()=incomplete("DELETE FROM local_visibility WHERE recording_id='SYN-CALL-2'")
    @Test fun missingLinkedTaskCannotBecomeReviewed()=incomplete("DELETE FROM tasks WHERE task_id='SYN-TASK-2'")
    @Test fun knownRevokedGrantIsExcludedBeforeSummaryRead() {
        db.execSQL("UPDATE folder_grants SET active=0");db.execSQL("UPDATE summary_versions SET summary_text=NULL")
        assertEquals(0,read().expectedCount);assertEquals(ReportReadiness.NO_RECORDINGS,read().readiness)
    }
    private fun assertSingleCanonicalCoverage(first:String,next:String) {
        context.deleteDatabase(DailyReportLedger.DATABASE);context.deleteDatabase("ieum-daily-reports-v1.db")
        db.execSQL("UPDATE recordings SET original_started_at_ms=?",arrayOf(Instant.parse("2026-10-10T20:00:00Z").toEpochMilli()))
        DailyReportLedger(context,"SYN-TZ-SQL").use { ledger ->
            val a=ledger.ensure(ReportWindow(window.date,ZoneId.of(first)),0)
            val b=ledger.ensure(ReportWindow(window.date.plusDays(1),ZoneId.of(next)),0)
            val source=CanonicalReviewedDailySource(context) { policy }
            val inputs=listOf(source.snapshot(a.window),source.snapshot(b.window))
            assertEquals(1,inputs.sumOf { it.expectedCount });assertEquals(1,inputs.sumOf { it.sources.size })
            assertEquals(1,inputs.count { it.fullyReviewed });assertEquals(a.window.until,b.window.from)
        }
    }
    @Test fun actualSqlContainsTwentyUtcSourceOnceAcrossLosAngelesToSeoul()=assertSingleCanonicalCoverage("America/Los_Angeles","Asia/Seoul")
    @Test fun actualSqlContainsTwentyUtcSourceOnceAcrossSeoulToLosAngeles()=assertSingleCanonicalCoverage("Asia/Seoul","America/Los_Angeles")
    @Test fun acceptedCanonicalSummaryWithMatchingSourceHashIsReviewedInput() {
        val input=read();assertTrue(input.fullyReviewed);assertEquals(1,input.expectedCount)
        assertEquals("SYN-ACCEPT-1",input.sources.single().reviewReceipt);assertEquals(text,input.preview())
    }
    @Test fun missingAcceptReceiptKeepsResultInReviewAndDoesNotPromoteIt() {
        db.execSQL("DELETE FROM receipts");val input=read()
        assertFalse(input.fullyReviewed);assertEquals(ReportReadiness.WAITING_REVIEW,input.readiness);assertTrue(input.sources.isEmpty())
    }
    @Test fun currentProcessingNotCompletedBlocksEvenOlderAcceptedSummary() {
        db.execSQL("UPDATE processing SET state='TRANSCRIBING'")
        val input=read();assertFalse(input.fullyReviewed);assertEquals(ReportReadiness.WAITING_ANALYSIS,input.readiness)
    }
    @Test fun currentSourceVersionDoesNotMatchOldSummaryAndCannotBecomeCompleted() {
        db.execSQL("UPDATE recordings SET source_version=2");db.execSQL("UPDATE source_versions SET source_version=2");db.execSQL("UPDATE processing SET source_version=2")
        assertEquals(ReportReadiness.WAITING_REVIEW,read().readiness)
    }
    @Test fun wrongSummaryHashIsRejected() {
        db.execSQL("UPDATE summary_versions SET summary_sha256=?",arrayOf("b".repeat(64)))
        assertFalse(read().fullyReviewed)
    }
    @Test fun excludedNumberIsRemovedBeforeSummaryContentIsRead() {
        val phone=requireNotNull(PrivacyRules.validatedPhone("01000000001"))
        policy.put("exclusions",JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash(phone))))
        db.execSQL("UPDATE summary_versions SET summary_text=NULL") // Reading this as a reviewed body would fail.
        val input=read();assertEquals(ReportReadiness.NO_RECORDINGS,input.readiness);assertEquals(0,input.expectedCount)
    }
    @Test fun rawGoogleProposalHeadIsOnlyReviewPendingNotCanonicalAcceptance() {
        db.execSQL("UPDATE recordings SET effective_summary_version=NULL");db.execSQL("UPDATE processing SET state='PENDING'")
        db.execSQL("CREATE TABLE google_native_proposal_heads(recording_id TEXT)")
        db.execSQL("INSERT INTO google_native_proposal_heads VALUES('SYN-CALL-1')")
        val input=read();assertEquals(ReportReadiness.WAITING_REVIEW,input.readiness);assertTrue(input.sources.isEmpty())
    }
    @Test fun manualCompletionAndTaskRevisionArePartOfFrozenBasis() {
        val before=read();db.execSQL("UPDATE tasks SET status='done',task_revision=2")
        val after=read();assertTrue(after.fullyReviewed);assertNotEquals(before.basisHash,after.basisHash)
        assertTrue(after.preview().contains("완료"));assertEquals(2L,after.sources.single().taskRevision)
    }
    @Test fun sameNumberDoesNotMergeSeparatePeopleAndBusinesses() {
        add("2");val input=read();assertTrue(input.fullyReviewed);assertEquals(2,input.sources.size)
        assertEquals(setOf("SYN-PERSON-1","SYN-PERSON-2"),input.sources.map { it.personId }.toSet())
        assertEquals(setOf("SYN-CASE-1","SYN-CASE-2"),input.sources.map { it.caseId }.toSet())
    }
    @Test fun archiveOrRevokedFolderIsNotAnInputForSend() {
        db.execSQL("UPDATE folder_grants SET active=0");assertEquals(ReportReadiness.NO_RECORDINGS,read().readiness)
    }
    @Test fun unresolvedOriginalTimeBlocksRatherThanAssumingAReportDay() {
        db.execSQL("UPDATE recordings SET original_started_at_ms=NULL");assertEquals(ReportReadiness.WAITING_REVIEW,read().readiness)
    }
    @Test fun manualMemoNeedsNewCanonicalReview() {
        db.execSQL("UPDATE recordings SET manual_text='합성: 직접 수정된 업무'")
        assertEquals(ReportReadiness.WAITING_REVIEW,read().readiness)
    }
    @Test fun deviceZoneWindowDoesNotUseExistingSeoulDateLabel() {
        val la=CanonicalReviewedDailySource(context) { policy }.snapshot(ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("America/Los_Angeles")))
        assertEquals(ReportReadiness.NO_RECORDINGS,la.readiness)
    }
    @Test fun policyEpochStaysMonotonicWhenAnExclusionIsRestored() {
        val settings=SecureSettings(context,object:SecureSettings.KeyAccess {
            private var key:javax.crypto.SecretKey?=null
            override fun existing()=key
            override fun createForFirstEmptyStore():javax.crypto.SecretKey=javax.crypto.spec.SecretKeySpec(ByteArray(32) { 9 },"AES").also { key=it }
        })
        settings.update { it.put("personalPhoneDisabled",true) };val first=settings.snapshot().getLong("dailyReportPolicyEpoch")
        settings.update { it.put("personalPhoneDisabled",false) };val second=settings.snapshot().getLong("dailyReportPolicyEpoch")
        settings.update { it.put("personalPhoneDisabled",true) };val third=settings.snapshot().getLong("dailyReportPolicyEpoch")
        assertTrue(second>first && third>second)
    }
}
