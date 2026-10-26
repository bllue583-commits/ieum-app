package kr.callbrief

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kr.callbrief.navermail.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Current host owner, pre-received generated mail only. No IMAP, credentials or account calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class NaverWorkflowUiTest:ProtectedTextHostTestBase() {
    companion object {lateinit var session:GoogleConnectionSession}
    class Host:NaverMailReviewActivity(){override fun createSession()=session}
    private class Memory:GoogleConnectionSession.Persistence{override fun read()=JSONObject();override fun write(value:JSONObject){}}
    private lateinit var context:Context;private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys;private var recording=""
    private val worker=Executors.newSingleThreadExecutor();private fun <T> io(f:()->T)=worker.submit<T>{f()}.get(30,TimeUnit.SECONDS)
    @Before fun seed(){context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory());keys=SyntheticPilotIntegrationTest.HostKeys();io{GeneralLocalStoreTest.host(context,keys).use{s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply{put()});val r=s.snapshot().getJSONObject(0);recording=r.getString("file_id");s.edit(recording,r,"PUBLIC_SYNTHETIC_NAVER_UI_NOTE","done")};val account=AccountBinding(GeneralLocalStore.OWNER,"fixture-mail@naver.com");val scope=MailScope(account,"unbound","unbound",setOf("fixture@example.invalid"),setOf("계약"),LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-12"),relationshipConfirmedByUser=true);val ref=MailRef(account.key,"INBOX",100,1);val header=HeaderEnvelope(ref,Instant.parse("2026-10-10T01:00:00Z"),"<fixture@example.invalid>",setOf("fixture@example.invalid"),setOf(account.address),"합성 계약 메일",kr.callbrief.core.PrivacyRules.hash("fixture-header"),"text/plain",origin=Origin.SYNTHETIC_FIXTURE);val candidate=MailCandidate(header,scope.fingerprint,Relation.CANDIDATE,listOf("사용자 선택"),listOf("의미 확인 필요"),BodyState.PLAIN_AVAILABLE);WorkflowNaverInbox(context,GeneralLocalStore.OWNER,null,{true}).replace(ScanBatch(account,scope,1,Origin.SYNTHETIC_FIXTURE,listOf(candidate),false,emptyList(),mapOf(ref to PlainBody(ref,"PUBLIC_SYNTHETIC_NAVER_UI_BODY",false,header.origin)),"UI-FIXTURE-RUN"))}}
    @After fun stop(){worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))}
    private fun field(a:NaverMailReviewActivity,n:String)=NaverMailReviewActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun root(a:NaverMailReviewActivity)=field(a,"root") as View
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun idle(a:NaverMailReviewActivity){repeat(300){shadowOf(Looper.getMainLooper()).idle();if(field(a,"busy")==false)return;Thread.sleep(10)};fail("Mail review did not settle")}
    private fun activity()=Robolectric.buildActivity(Host::class.java).setup().also{idle(it.get())}
    private fun button(a:NaverMailReviewActivity,label:String)=views(root(a)).filterIsInstance<Button>().single{it.text.toString()==label}
    private fun dialog()=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    private fun snap()=io{RecordingWorkflowStore(context).use{it.snapshot(recording)}}
    @Test fun M01ExplicitGlobalMailSelectionSavesToSameRecordWithoutApplyingTasks(){val a=activity().get();button(a,"선택 통화와 관련성 직접 확인").performClick();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);assertEquals(1,snap().getJSONArray("related").length());assertEquals("PUBLIC_SYNTHETIC_NAVER_UI_BODY",snap().getJSONArray("related").getJSONObject(0).getString("raw_text"));assertEquals(0,snap().getJSONArray("items").length());assertEquals("done",snap().getString("manual_status"));assertEquals("PUBLIC_SYNTHETIC_NAVER_UI_NOTE",snap().getString("manual_text"))}
    @Test fun M02CancelAndStaleRecordingSelectionNeverClaimSaved(){val a=activity().get();button(a,"선택 통화와 관련성 직접 확인").performClick();dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(0,snap().getJSONArray("related").length());button(a,"선택 통화와 관련성 직접 확인").performClick();io{GeneralLocalStoreTest.host(context,keys).use{s->s.edit(recording,s.snapshot().getJSONObject(0),"PUBLIC_SYNTHETIC_CONCURRENT_NOTE","done")}};dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);assertEquals(0,snap().getJSONArray("related").length());assertTrue(views(root(a)).filterIsInstance<TextView>().any{it.text.toString().contains("읽기·저장을 확인하지 못")});assertFalse(views(root(a)).filterIsInstance<TextView>().any{it.text.toString().contains("근거로 저장했어요")})}
    @Test fun M03DestroyedReviewCannotShowLateDialog(){val c=activity();val a=c.get();val old=button(a,"원문·근거 확인");val before=ShadowAlertDialog.getLatestAlertDialog();c.pause().stop().destroy();old.performClick();shadowOf(Looper.getMainLooper()).idle();assertSame(before,ShadowAlertDialog.getLatestAlertDialog())}
}
