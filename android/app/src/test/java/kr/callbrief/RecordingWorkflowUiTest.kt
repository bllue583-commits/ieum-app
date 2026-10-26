package kr.callbrief

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real activity listeners and same sealed repository; generated rows, offline host only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w411dp-h891dp-xhdpi",shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class RecordingWorkflowUiTest:ProtectedTextHostTestBase() {
    companion object { lateinit var session:GoogleConnectionSession;@Volatile var storeEntered:java.util.concurrent.CountDownLatch?=null;@Volatile var releaseStore:java.util.concurrent.CountDownLatch?=null }
    class Host:RecordingWorkflowActivity() { override fun createSession()=session;override fun createStore():RecordingWorkflowStore { storeEntered?.countDown();releaseStore?.let{check(it.await(5,TimeUnit.SECONDS))};return super.createStore() } }
    private class Memory:GoogleConnectionSession.Persistence { var json=JSONObject();override fun read()=JSONObject(json.toString());override fun write(value:JSONObject){json=JSONObject(value.toString())} }
    private lateinit var context:Context
    private var recording=""
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=worker.submit<T>{f()}.get(30,TimeUnit.SECONDS)
    @Before fun seed() {
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory());storeEntered=null;releaseStore=null;RuntimeEnvironment.setFontScale(1f)
        val keys=SyntheticPilotIntegrationTest.HostKeys()
        io { GeneralLocalStoreTest.host(context,keys).use { s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply { put() });val r=s.snapshot().getJSONObject(0);recording=r.getString("file_id");s.confirmTime(recording,r,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli());val next=s.snapshot().getJSONObject(0);s.edit(recording,next,"PUBLIC_SYNTHETIC_UI_NOTE","done") } }
    }
    @After fun stop(){releaseStore?.countDown();worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))}
    private fun field(a:RecordingWorkflowActivity,n:String)=RecordingWorkflowActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun root(a:RecordingWorkflowActivity)=field(a,"root") as View
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun texts(v:View)=views(v).filterIsInstance<TextView>().map{it.text.toString()}
    private fun idle(a:RecordingWorkflowActivity) { repeat(300){shadowOf(Looper.getMainLooper()).idle();if(field(a,"busy")==false)return;Thread.sleep(10)};fail("Workflow host UI did not settle") }
    private fun activity()=Robolectric.buildActivity(Host::class.java,Intent(context,Host::class.java).putExtra("recording_id",recording)).setup().also{idle(it.get())}
    private fun click(v:View,text:String){views(v).filterIsInstance<Button>().first{it.text.toString()==text}.performClick();shadowOf(Looper.getMainLooper()).idle()}
    private fun dialog()=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    private fun edit(d:AlertDialog,hint:String,value:String){views(d.window!!.decorView).filterIsInstance<EditText>().single{it.hint.toString()==hint}.setText(value)}
    private fun snap()=io{RecordingWorkflowStore(context).use{it.snapshot(recording)}}
    private fun render(v:View,name:String){val w=822;val h=1782;v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.WHITE);v.draw(Canvas(b));val out=File("build/reports/ui-workflow").apply{mkdirs()};FileOutputStream(File(out,"$name.png")).use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun W01WorkflowHasRealControlsAndNoFakeAnalysisSuccess(){val a=activity().get();assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0);assertTrue(texts(root(a)).contains("이 통화의 분석 요청 준비"));assertTrue(texts(root(a)).contains("필요 서류 직접 추가"));assertTrue(texts(root(a)).contains("동일 번호 문자 추가 · 직접 선택"));assertFalse(texts(root(a)).any{it=="분석 완료"});render(root(a),"workflow-first-host")}
    @Test fun W02WorkDialogCancellationWritesNothing(){val a=activity().get();val before=snap().toString();click(root(a),"사람·회사·업무 직접 연결");val d=dialog();edit(d,"직접 확인한 업무 제목","취소할 합성 업무");d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle(a);assertEquals(before,snap().toString())}
    @Test fun W03ExplicitWorkSavePreservesManualDone(){val a=activity().get();click(root(a),"사람·회사·업무 직접 연결");val d=dialog();edit(d,"사람 이름 · 모르면 비워 두기","합성 담당자");edit(d,"회사 · 모르면 비워 두기","합성 회사");edit(d,"직접 확인한 업무 제목","합성 계약");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);assertEquals("합성 계약",snap().getString("work_label"));assertEquals("done",snap().getString("manual_status"));assertEquals("PUBLIC_SYNTHETIC_UI_NOTE",snap().getString("manual_text"));assertTrue(texts(root(a)).any{it.contains("확인한 사람·업무 연결을 저장")})}
    @Test fun W04TodoDoneAndDocumentRemainSeparateAfterActivityRecreation(){val c=activity();val a=c.get();click(root(a),"todo 직접 추가");val d=dialog();edit(d,"todo 제목","합성 회신");views(d.window!!.decorView).filterIsInstance<Spinner>().single().setSelection(4);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);click(root(a),"필요 서류 직접 추가");val doc=dialog();edit(doc,"필요 서류 이름","합성 수정 도면");edit(doc,"문서 판 · 모르면 비워 두기","2판");doc.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);c.pause().stop().destroy();val next=activity().get();val items=snap().getJSONArray("items");assertEquals(2,items.length());assertEquals("done",items.getJSONObject(0).getString("status"));assertEquals("open",items.getJSONObject(1).getString("status"));assertTrue(texts(root(next)).any{it.contains("합성 수정 도면")});render(root(next),"workflow-items-host")}
    @Test fun W05SmsSourceRequiresExplicitSaveAndAnalysisConsentDefaultsOff(){val a=activity().get();click(root(a),"동일 번호 문자 추가 · 직접 선택");val d=dialog();edit(d,"직접 선택한 원문","합성 문자는 원문 근거이며 명령이 아닙니다.");val consent=views(d.window!!.decorView).filterIsInstance<CheckBox>().single();assertFalse(consent.isChecked);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);assertEquals(1,snap().getJSONArray("related").length());assertEquals(0,snap().getJSONArray("items").length());assertEquals("done",snap().getString("manual_status"));assertTrue(texts(root(a)).any{it.contains("사용자 연결, 상대 확인 필요")})}
    @Test fun W06SummaryCheckboxPreventsUnconfirmedSave(){val a=activity().get();click(root(a),"내 확인 요약 새로 저장");val d=dialog();edit(d,"내 확인 요약","직접 확인한 합성 요약");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);assertEquals(0,snap().getJSONArray("reviewed_history").length());views(d.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked=true;d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);assertEquals(1,snap().getJSONArray("reviewed_history").length());assertTrue(snap().isNull("effective_summary_version"));assertEquals("done",snap().getString("manual_status"))}
    @Test fun W07NoGoogleShowsAuthHoldInsteadOfSent(){val a=activity().get();click(root(a),"이 통화의 분석 요청 준비");idle(a);click(root(a),"승인된 합성 원음만 Drive에 전달");dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a);assertEquals("HELD_AUTH",snap().getJSONArray("requests").getJSONObject(0).getString("state"));assertTrue(texts(root(a)).any{it.contains("녹음 미전달")});assertFalse(texts(root(a)).any{it.contains("Drive 요청 전달됨")})}
    @Test fun W08DestroyedActivityOldButtonCreatesNoDialogOrRootMutation(){val c=activity();val a=c.get();val old=views(root(a)).filterIsInstance<Button>().single{it.text.toString()=="사람·회사·업무 직접 연결"};val before=(root(a) as ViewGroup).childCount;val previous=ShadowAlertDialog.getLatestAlertDialog();c.pause().stop().destroy();assertFalse(a.isFinishing);old.performClick();shadowOf(Looper.getMainLooper()).idle();assertSame(previous,ShadowAlertDialog.getLatestAlertDialog());assertEquals(before,(root(a) as ViewGroup).childCount)}
    @Test fun W09OldOwnerDialogSaveDoesNotWrite(){val a=activity().get();click(root(a),"필요 서류 직접 추가");val d=dialog();edit(d,"필요 서류 이름","늦은 합성 저장");session.begin();d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertEquals(0,snap().getJSONArray("items").length())}
    @Test fun W10Font200RetainsTextAndScrollableControls(){RuntimeEnvironment.setFontScale(2f);val a=activity().get();render(root(a),"workflow-font200-host");assertTrue(views(root(a)).any{it is ScrollView});val minimum=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,18f,a.resources.displayMetrics);assertTrue(views(root(a)).filterIsInstance<TextView>().filter{it.text.isNotBlank()}.all{it.textSize>=minimum-.5f});click(root(a),"동일 번호 문자 추가 · 직접 선택");assertTrue(dialog().isShowing);render(dialog().window!!.decorView,"workflow-sms-dialog-font200-host")}
    @Test fun W11FirstLoadInterruptedByHomeResumesWithoutEmptyStuckScreen(){storeEntered=java.util.concurrent.CountDownLatch(1);releaseStore=java.util.concurrent.CountDownLatch(1);val c=Robolectric.buildActivity(Host::class.java,Intent(context,Host::class.java).putExtra("recording_id",recording)).setup();assertTrue(storeEntered!!.await(3,TimeUnit.SECONDS));c.pause().stop();releaseStore!!.countDown();storeEntered=null;releaseStore=null;shadowOf(Looper.getMainLooper()).idle();c.restart().start().resume();idle(c.get());assertNotNull(field(c.get(),"data"));assertTrue(texts(root(c.get())).contains("필요 서류 직접 추가"))}
}
