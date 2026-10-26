package kr.callbrief

import android.content.Context
import kr.callbrief.kakao.core.DeliveryCoordinator
import kr.callbrief.kakao.core.DeliveryResult
import kr.callbrief.kakao.core.DailyReport
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartialDailyReportIntegrationTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var ledger:DailyReportLedger
    private val window=ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul"))
    private val env=ReportEnvironment(Instant.parse("2026-10-10T22:00:00Z"),window.zone,elapsed=100,boot=1)
    private val cipher=FixtureCipher()
    private class FixtureCipher:ReportPayloadCipher {
        val key=SecretKeySpec(ByteArray(32){19},"AES");var missing=false;val existing=mutableListOf<Boolean>()
        override fun seal(text:String,aad:String,hasExistingPayload:Boolean):ByteArray {
            check(!missing || !hasExistingPayload) { "REPORT_KEY_MISSING_REVIEW_REQUIRED" };existing+=hasExistingPayload
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(aad.toByteArray());return c.iv+c.doFinal(text.toByteArray())
        }
        override fun open(bytes:ByteArray,aad:String):String { check(!missing) { "REPORT_KEY_MISSING_REVIEW_REQUIRED" };val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)));c.updateAAD(aad.toByteArray());return String(c.doFinal(bytes.copyOfRange(12,bytes.size))) }
    }
    private fun reviewed(id:String="confirmed")=ReviewedReportSource(id,"a".repeat(64),1,1,DeliveryCoordinator.sha256("PUBLIC_SYNTHETIC_REVIEWED_BODY"),"user-receipt",1,1,1,"PUBLIC_SYNTHETIC_REVIEWED_BODY",taskStatus="done")
    private fun pending(id:String="failed",reason:String="ANALYSIS_FAILED",known:Boolean=true)=ReportPendingSource(id,1,"b".repeat(64),1,1,1,reason,"FAILED",known)
    private fun input(reason:String="ANALYSIS_FAILED")=DailyReportInput(ReportReadiness.WAITING_ANALYSIS,listOf(reviewed()),2,1,"c".repeat(64),listOf(pending(reason=reason)))
    private fun ready()=DailyReportInput(ReportReadiness.REVIEWED,listOf(reviewed()),1,0,"c".repeat(64))
    private fun report()=ledger.ensure(window,ledger.config().generation)
    private fun save(value:DailyReportInput=input())=ledger.storePartial(report().id,value,env.now,ledger.config().generation)
    private fun blocked(f:()->Unit) { try { f();fail("unsafe operation admitted") } catch(_:IllegalStateException){} }
    private fun evidence(id:String,vararg fields:Pair<String,Any>) { val value=JSONObject().put("id",id).put("scope","PROTECTED_SQLITE_JCE_FAKE_CLOCK_NO_ANDROID_OS_OR_SEND").put("pass",true);fields.forEach { value.put(it.first,it.second) };File("build/reports/partial-daily-report").apply { mkdirs() }.resolve("$id.json").writeText(value.toString(2)) }
    @Before fun start() { context=RuntimeEnvironment.getApplication();ledger=DailyReportLedger(context,"SYNTHETIC-PARTIAL-PROFILE",cipher);ledger.setEnabled(true,env.now,env.zone) }
    @After fun stop() { ledger.close() }
    @Test fun P01PendingAndFailedRemainInStoredPartialNotDispatch() { val key=save();val r=report();val text=ledger.partialText(r.id,key);assertTrue(text.contains("전체 완료 아님"));assertTrue(text.contains("분석 실패"));assertTrue(text.contains("PUBLIC_SYNTHETIC_REVIEWED_BODY"));assertEquals(ReportDelivery.NOT_STORED,r.delivery);assertEquals("",r.basisHash);assertNull(ledger.claim(r.id,input(),env,ledger.config().generation));evidence("P01","partial" to text,"send_claim" to false) }
    @Test fun P02DuplicateObservationReusesImmutableSnapshot() { val one=save();val two=save();assertEquals(one,two);assertEquals(1,ledger.partialHistory(report().id).size);assertEquals(1,cipher.existing.size);evidence("P02") }
    @Test fun P03ChangedFailureStateCreatesNewHistoryWithoutReplacingOld() { val key=save();val old=ledger.partialText(report().id,key);val next=save(input("CURRENT_REQUEST_UNKNOWN"));assertNotEquals(key,next);assertEquals(old,ledger.partialText(report().id,key));assertEquals(2,ledger.partialHistory(report().id).size);assertTrue(ledger.partialText(report().id,next).contains("불확실"));evidence("P03") }
    @Test fun P04ReopenKeepsPartialCipherAndEvidence() { val key=save();val id=report().id;val text=ledger.partialText(id,key);ledger.close();ledger=DailyReportLedger(context,"SYNTHETIC-PARTIAL-PROFILE",cipher);assertEquals(text,ledger.partialText(id,key));evidence("P04") }
    @Test fun P05PartialCannotSatisfyFinalFreezeOrCompletionGate() { val value=input();val key=save(value);blocked { ledger.freeze(report().id,value,value.basisHash,"합성 완료",true,ledger.config().generation) };assertEquals(ReportDelivery.NOT_STORED,report().delivery);assertEquals(1,ledger.partialHistory(report().id).size);assertTrue(ledger.partialText(report().id,key).contains("전송 대상 아님"));evidence("P05") }
    @Test fun P06ReviewedFinalDoesNotErasePartialFailureHistory() { val key=save();val original=ledger.partialText(report().id,key);val value=ready();ledger.freeze(report().id,value,value.basisHash,value.preview(),true,ledger.config().generation);assertEquals(ReportDelivery.SEND_WAITING,report().delivery);assertEquals(original,ledger.partialText(report().id,key));assertEquals(listOf(false,true),cipher.existing);evidence("P06") }
    @Test fun P07MissingKeyDoesNotReinitializeAfterExistingPartial() { save();cipher.missing=true;blocked { save(input("CURRENT_REQUEST_UNKNOWN")) };blocked { val value=ready();ledger.freeze(report().id,value,value.basisHash,value.preview(),true,ledger.config().generation) };assertEquals(1,ledger.partialHistory(report().id).size);assertEquals(ReportDelivery.NOT_STORED,report().delivery);evidence("P07") }
    @Test fun P08ActualProtectedFilesContainNoPartialPlaintext() { save();context.noBackupFilesDir.walkTopDown().filter { it.isFile }.forEach { assertFalse(it.path,String(it.readBytes(),Charsets.UTF_8).contains("PUBLIC_SYNTHETIC_REVIEWED_BODY")) };evidence("P08") }
    @Test fun P09UnknownTimeIsNotAssignedToReportDate() { val value=DailyReportInput(ReportReadiness.WAITING_REVIEW,emptyList(),1,1,"c".repeat(64),listOf(pending(reason="IDENTITY_TIME_UNCONFIRMED",known=false)));val text=ledger.partialText(report().id,save(value));assertTrue(text.contains("통화 날짜 미확인"));assertTrue(text.contains("이 날짜로 확정하지 않음"));evidence("P09") }
    @Test fun P10SourceReadFailureRemainsUnknownInventoryNotZeroComplete() { val value=DailyReportInput(ReportReadiness.SOURCE_FAILURE,emptyList(),0,1,"c".repeat(64),listOf(pending("inventory","INVENTORY_UNAVAILABLE",false)));val text=ledger.partialText(report().id,save(value));assertTrue(text.contains("대상 수 미확정"));assertFalse(value.fullyReviewed);assertFalse(text.contains("대상 0개"));evidence("P10") }
    @Test fun P11CoordinatorAutoStoresPartialWithoutSenderAdmission()=runBlocking { var sends=0;val sender=object:DailyReportSender {override fun gate():DeliveryResult?=null;override suspend fun send(report:DailyReport):DeliveryResult {sends++;return DeliveryResult(DeliveryResult.Status.SENT)}};report();DailyReportCoordinator(ledger,{input()},sender,{env}).tick();assertEquals(0,sends);assertEquals(1,ledger.partialHistory(report().id).size);assertEquals(ReportDelivery.NOT_STORED,report().delivery);evidence("P11") }
    @Test fun P12UnresolvedDetailCannotHideBehindReviewedFlag() { val value=ready().copy(unresolved=listOf(pending()),pendingCount=0);assertFalse(value.fullyReviewed);assertTrue(value.partialPreview().contains("분석 실패"));evidence("P12") }
}
