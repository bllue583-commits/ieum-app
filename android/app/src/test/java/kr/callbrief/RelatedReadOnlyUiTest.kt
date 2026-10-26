package kr.callbrief

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kr.callbrief.navermail.android.NaverCredentialVault
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class RelatedReadOnlyUiTest:ProtectedTextHostTestBase() {
    companion object{lateinit var session:GoogleConnectionSession}
    class Host:RelatedReadOnlyActivity(){override fun createSession()=session;override fun savedAccounts(context:Context)=emptyList<NaverCredentialVault.SavedAccount>()}
    private class Memory:GoogleConnectionSession.Persistence{override fun read()=JSONObject();override fun write(value:JSONObject){}}
    private lateinit var context:Context;private var id="";private lateinit var provider:RelatedReadOnlyIntegrationTest.SmsProvider
    private val io=Executors.newSingleThreadExecutor();private fun <T> work(f:()->T)=io.submit<T>{f()}.get(30,TimeUnit.SECONDS)
    @Before fun setup(){context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory());provider=RelatedReadOnlyIntegrationTest.SmsProvider();provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="sms"});ShadowContentResolver.registerProviderInternal("sms",provider);shadowOf(context as Application).grantPermissions(android.Manifest.permission.READ_SMS)
        work{GeneralLocalStoreTest.host(context,SyntheticPilotIntegrationTest.HostKeys()).use{s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply{put()});val r=s.snapshot().getJSONObject(0);id=r.getString("file_id");s.edit(id,r,"PUBLIC_UI_DONE","done")};RecordingWorkflowStore(context).use{s->s.confirmWork(id,s.snapshot(id),"합성 사람","합성 회사","합성 업무","010"+"0".repeat(8))}}
    }
    @After fun stop(){io.shutdown();assertTrue(io.awaitTermination(5,TimeUnit.SECONDS))}
    private fun field(a:RelatedReadOnlyActivity,key:String)=RelatedReadOnlyActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.get(a)
    private fun root(a:RelatedReadOnlyActivity)=field(a,"root") as View
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun idle(a:RelatedReadOnlyActivity){repeat(300){shadowOf(Looper.getMainLooper()).idle();if(field(a,"busy")==false)return;Thread.sleep(10)};fail("Related UI did not settle")}
    private fun launch()=Robolectric.buildActivity(Host::class.java,Intent(context,Host::class.java).putExtra("recording_id",id)).setup().also{idle(it.get())}
    private fun configure()=work{RelatedReadOnlyStore(context).use{s->val expected=RecordingWorkflowStore(context).use{it.snapshot(id)};s.configureSms(id,expected,1000,4000,true)}}
    private fun picture(v:View){v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1600);val b=android.graphics.Bitmap.createBitmap(720,1600,android.graphics.Bitmap.Config.ARGB_8888);v.draw(android.graphics.Canvas(b));val d=File("build/reports/related-readonly").apply{mkdirs()};File(d,"saved-sms-auto-refresh-host.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun U01UnconfiguredNormalEntryHasNoProviderRead(){val c=launch();val a=c.get();assertEquals(0,provider.metadataQueries);assertTrue(views(root(a)).any{it.tag=="related_sms_configure"});assertTrue(views(root(a)).filterIsInstance<TextView>().any{it.text.toString().contains("실제 네이버 계정 읽기가 차단")});c.pause().stop().destroy()}
    @Test fun U02SavedScopeAutomaticallyRefreshesOnFirstOpenAndResume(){configure();val c=launch();val a=c.get();assertEquals(1,provider.bodyQueries);assertTrue(views(root(a)).any{it.tag=="related_sms_confirm"});picture(root(a));c.pause().stop();provider.rows.single().text="PUBLIC_UI_SMS_NEW_BODY";c.restart().start().resume();idle(a);assertEquals(2,provider.bodyQueries);work{RelatedReadOnlyStore(context).use{assertEquals(2L,it.smsCandidates(id).getJSONObject(0).getLong("version"))};RecordingWorkflowStore(context).use{assertEquals("done",it.snapshot(id).getString("manual_status"))}};c.pause().stop().destroy()}
    @Test fun U03UncheckedScopeConsentAndCancelDoNotSaveOrRead(){val c=launch();val a=c.get();views(root(a)).filterIsInstance<Button>().single{it.tag=="related_sms_configure"}.performClick();val d=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog;d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();work{RelatedReadOnlyStore(context).use{assertNull(it.smsScope(id))}};assertEquals(0,provider.metadataQueries);d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();c.pause().stop().destroy()}
    @Test fun U04DestroyedActivityCannotShowDialogOrRead(){val c=launch();val a=c.get();val b=views(root(a)).filterIsInstance<Button>().single{it.tag=="related_sms_configure"};val before=ShadowAlertDialog.getLatestAlertDialog();c.pause().stop().destroy();b.performClick();shadowOf(Looper.getMainLooper()).idle();assertSame(before,ShadowAlertDialog.getLatestAlertDialog());assertEquals(0,provider.metadataQueries)}
    @Test fun U05OldProviderReturnAfterStopResumeCannotPublish(){configure();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val first=java.util.concurrent.atomic.AtomicBoolean(true);provider.afterMetadata={if(first.compareAndSet(true,false)){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}};val c=Robolectric.buildActivity(Host::class.java,Intent(context,Host::class.java).putExtra("recording_id",id)).setup();val a=c.get();assertTrue(entered.await(3,TimeUnit.SECONDS));c.pause().stop();provider.rows.single().text="PUBLIC_RESUME_ONLY_BODY";c.restart().start().resume();idle(a);release.countDown();Thread.sleep(100);shadowOf(Looper.getMainLooper()).idle();assertEquals(1,provider.bodyQueries);work{RelatedReadOnlyStore(context).use{val rows=it.smsCandidates(id);assertEquals(1,rows.length());assertEquals("PUBLIC_RESUME_ONLY_BODY",rows.getJSONObject(0).getString("raw_text"))}};c.pause().stop().destroy()}
    @Test fun U06ProviderErrorKeepsPriorCandidatesAndShowsFailure(){configure();val c=launch();val a=c.get();provider.nullResult=true;views(root(a)).filterIsInstance<Button>().single{it.tag=="related_sms_refresh"}.performClick();idle(a);assertTrue(views(root(a)).filterIsInstance<TextView>().any{it.tag=="related_read_status"&&it.text.toString().contains("확인하지 못")});assertTrue(views(root(a)).any{it.tag=="related_sms_confirm"});work{RelatedReadOnlyStore(context).use{assertEquals(1,it.smsCandidates(id).length())}};c.pause().stop().destroy()}
    @Test fun U07OldFinallyCannotClearNewPendingReader(){configure();val oldEntered=java.util.concurrent.CountDownLatch(1);val newEntered=java.util.concurrent.CountDownLatch(1);val releaseOld=java.util.concurrent.CountDownLatch(1);val releaseNew=java.util.concurrent.CountDownLatch(1);val calls=java.util.concurrent.atomic.AtomicInteger();provider.afterMetadata={if(calls.incrementAndGet()==1){oldEntered.countDown();check(releaseOld.await(5,TimeUnit.SECONDS))}};provider.afterBody={newEntered.countDown();check(releaseNew.await(5,TimeUnit.SECONDS))};val c=Robolectric.buildActivity(Host::class.java,Intent(context,Host::class.java).putExtra("recording_id",id)).setup();val a=c.get();try{assertTrue(oldEntered.await(3,TimeUnit.SECONDS));val oldJob=field(a,"job") as kotlinx.coroutines.Job;c.pause().stop();c.restart().start().resume();assertTrue(newEntered.await(3,TimeUnit.SECONDS));releaseOld.countDown();repeat(150){shadowOf(Looper.getMainLooper()).idle();if(!oldJob.isCompleted)Thread.sleep(10)};assertTrue(oldJob.isCompleted);assertNotNull("New reader remains cancellable after old finally",field(a,"reader"));val before=work{RelatedReadOnlyStore(context).use{it.smsCandidates(id).length()}};c.pause().stop();assertEquals(true,field(a,"stopped"));val atStop=work{RelatedReadOnlyStore(context).use{it.smsCandidates(id).length()}};releaseNew.countDown();Thread.sleep(100);shadowOf(Looper.getMainLooper()).idle();assertEquals(1,provider.bodyQueries);val after=work{RelatedReadOnlyStore(context).use{it.smsCandidates(id).length()}};val folder=File("build/reports/related-readonly").apply{mkdirs()};File(folder,"U07-admission-timing.json").writeText(JSONObject().put("before_lifecycle_transition_candidates",before).put("at_stop_handler_return_candidates",atStop).put("after_stop_candidates",after).put("query_admitted_before_stop",1).put("scope","HOST_HANDLE_AND_NO_NEW_COMMIT_AFTER_STOP_NOT_DURABLE_TR").toString(2));assertEquals(atStop,after)}finally{releaseOld.countDown();releaseNew.countDown();c.destroy()}}
}
