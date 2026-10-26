package kr.callbrief

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kr.callagent.drive.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Existing frozen RO1 example bytes, fake trusted session/port only. The imported
 * fixture's STT claim is not proof of this test running a model/native account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DriveAForegroundUiTest:ProtectedTextHostTestBase() {
    companion object {internal lateinit var fake:Port}
    internal class Host:DriveAForegroundActivity(){override fun onCreate(saved:android.os.Bundle?){setTheme(R.style.Theme_CallBrief);super.onCreate(saved)};override fun createCandidate()=DriveAForegroundCandidate(this,fake);override fun diagnose()=JSONObject().put("package","public.fixture").put("versionName","LOCAL_UI_ONLY").put("gmsLocalStatusCode",1)}
    internal class Port(context:Context):DriveAForegroundPort {
        val bytes=context.assets.open("ro1-synthetic/request.json").use{it.readBytes()};val request=WireV0Codec.validateRequest(bytes)
        val owner=StrictJson.`object`(request["ownerBinding"]);val binding=DriveALocalScope(owner["ownerId"] as String,owner["driveAccount"] as String,owner["ownerId"] as String,"PUBLIC_FIXTURE_SESSION",3)
        var active=true;var polls=0;var exports=0;var receipt:DriveAReadOnlyReceipt?=null;var hidden=false;var block=false
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val ro1=context.assets.open("ro1-synthetic/result.json").use{it.readBytes()}
        var requestBytes=bytes.clone();var claimedApproval=true
        override fun scope()=if(active)binding else null
        override fun frozenRequest(request:String)=requestBytes.clone()
        override fun snapshot(local:String):DriveAExportSnapshot {
            exports++;val state=request.toMutableMap();state["stateRevision"]=0L;state["counterpartConfirmed"]=false;state["syntheticAudioApproved"]=claimedApproval;state["consentGranted"]=true;state["audioAvailable"]=true
            for(k in listOf("excluded","revoked","deleted","archived"))state[k]=false
            val reg=mapOf("state" to state,"files" to mapOf("audio" to mapOf("fileId" to request["audioFileId"]),"request" to mapOf("fileId" to request["requestFileId"]),"result" to mapOf("fileId" to request["resultFileId"])),"jobId" to request["jobId"],"folderId" to "public_fixture_folder","creatorModes" to listOf("ANDROID_APP"),"sessionBinding" to binding.binding(),"driveOwnerPermissionId" to binding.permissionId,"sessionEvidence" to "LOCAL_AUTHORIZATIONCLIENT_AND_DRIVE_ABOUT_OWNER_VERIFIED","nativeAppSessionAuthentication" to "GOOGLE_DRIVE_OWNER_VERIFIED_NOT_OIDC_SERVER_ATTESTED","requestDigest" to request["requestDigest"])
            return DriveAExportSnapshot(request["jobId"] as String,"public_fixture_folder",requestBytes.clone(),StrictJson.stringify(reg))
        }
        override fun reopen(request:String)=receipt?.copy(hidden=hidden,raw=if(hidden)null else receipt?.raw,analysis=if(hidden)null else receipt?.analysis)
        override fun pollExplicit(request:String):DriveAReadbackObservation {
            polls++;if(block){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
            val v=ReadonlyV1Codec().validate(ro1,bytes)
            receipt=DriveAReadOnlyReceipt(this.request["jobId"] as String,this.request["resultFileId"] as String,ReadonlyV1Codec.KIND,1,v.revision,v.digest,DriveRelay.sha256(ro1),"PROPOSAL",v.rawText,StrictJson.`object`(v.envelope["readOnlyAnalysis"])["rawAnalysisJson"] as String,false,false)
            return DriveAReadbackObservation("PROVENANCE_PENDING",reopen(request))
        }
    }
    private lateinit var context:Context
    private val id="public-fixture-local-request"
    @Before fun setup(){context=RuntimeEnvironment.getApplication();RuntimeEnvironment.setFontScale(1f);fake=Port(context)}
    @After fun done(){fake.release.countDown()}
    private fun field(a:DriveAForegroundActivity,n:String)=DriveAForegroundActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun root(a:DriveAForegroundActivity)=field(a,"root") as ViewGroup
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun labels(a:DriveAForegroundActivity)=views(root(a)).filterIsInstance<TextView>().map{it.text.toString()}
    private fun button(a:DriveAForegroundActivity,tag:String)=views(root(a)).filterIsInstance<Button>().single{it.tag==tag}
    private fun settle(a:DriveAForegroundActivity){repeat(400){Shadows.shadowOf(Looper.getMainLooper()).idle();if(field(a,"busy")==false)return;Thread.sleep(10)};fail("DRIVE_A_UI_NOT_SETTLED")}
    private fun open()=Robolectric.buildActivity(Host::class.java,Intent(context,Host::class.java).putExtra("local_request_id",id)).setup().also{settle(it.get())}
    private fun prepare()=runBlocking(Dispatchers.IO){DriveAForegroundCandidate(context,fake).prepareExplicit(id)}
    private fun render(a:DriveAForegroundActivity,name:String){val v=root(a);v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1600);val b=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/reports/drive-a-ui/screens").apply{mkdirs()}.let{FileOutputStream(File(it,"$name.png")).use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
    private fun proof(id:String){File("build/reports/drive-a-ui").apply{mkdirs()}.let{File(it,"$id.json").writeText(JSONObject().put("id",id).put("pass",true).put("scope","API35_NATIVE_HOST_UI_FROZEN_RO1_IMPORTED_FIXTURE_FAKE_PORT_ONLY").put("actualModelExecution",0).put("actualNetworkCalls",0).put("actualDTStatus","NOT_RUN").put("currentRemoteRegistration","NOT_VERIFIED").toString(2))}}
    @Test fun U01UnverifiedGrantDoesNotReadOrShareAndIgnoresIntentIdentityClaims(){fake.active=false;val c=Robolectric.buildActivity(Host::class.java,Intent(context,Host::class.java).putExtra("local_request_id",id).putExtra("ownerId","public-forged-owner").putExtra("accessToken","public-forged-token")).setup();settle(c.get());assertTrue(labels(c.get()).any{it.contains("현재 Drive 접근 확인")});assertEquals(0,fake.polls);assertEquals(0,fake.exports);assertTrue(views(root(c.get())).none{it.tag=="drive_a_share"||it.tag=="drive_a_poll"});assertNull(Shadows.shadowOf(c.get()).nextStartedActivity);render(c.get(),"U01-grant-held");c.pause().stop().destroy();proof("U01")}
    @Test fun U02PreparationDoesNotStartShareOrPollAndUsesLargeTouchTargets(){val c=open();button(c.get(),"drive_a_prepare").performClick();settle(c.get());assertTrue(labels(c.get()).any{it.contains("접수·실행은 미확인")});assertEquals(0,fake.polls);assertNull(Shadows.shadowOf(c.get()).nextStartedActivity);for(b in views(root(c.get())).filterIsInstance<Button>())assertTrue(b.minHeight>=56*context.resources.displayMetrics.density);render(c.get(),"U02-prepared");c.pause().stop().destroy();proof("U02")}
    @Test fun U03FrozenRo1ImportDisplaysExactRawAndReadonlyAnalysisWithoutExecutionTrust(){prepare();val c=open();button(c.get(),"drive_a_poll").performClick();settle(c.get());val raw=views(root(c.get())).filterIsInstance<TextView>().single{it.tag=="drive_a_raw"};assertEquals(ReadonlyV1Codec().validate(fake.ro1,fake.bytes).rawText,raw.text.toString());assertTrue(labels(c.get()).any{it.contains("실행 출처 미확인")});button(c.get(),"drive_a_analysis").performClick();val dialog=Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());assertTrue(dialog.title.toString().contains("출처 미확인"));val r=runBlocking{DriveAForegroundCandidate(context,fake).load(id)};assertEquals(ReadonlyV1Codec.KIND,r.receipt!!.kind);assertFalse(r.modelExecutionVerified);assertFalse(r.receipt.writerVerified);assertEquals(1,fake.polls);render(c.get(),"U03-ro1-readonly");c.pause().stop().destroy();proof("U03")}
    @Test fun U04ArchiveHidesRawAndAnalysisAfterReopen(){prepare();runBlocking{DriveAForegroundCandidate(context,fake).pollExplicit(id)};fake.hidden=true;val c=open();assertTrue(labels(c.get()).any{it.contains("보관·격리")});assertTrue(views(root(c.get())).none{it.tag=="drive_a_raw"||it.tag=="drive_a_analysis"});c.pause().stop().destroy();proof("U04")}
    @Test fun U05StopDuringReplyCannotAdmitNewJournalObservation(){prepare();fake.block=true;val c=open();button(c.get(),"drive_a_poll").performClick();assertTrue(fake.entered.await(5,TimeUnit.SECONDS));c.pause().stop().destroy();fake.release.countDown();repeat(50){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};runBlocking(Dispatchers.IO){TextStoreTestSupport.open(AccountStorageContext(context,fake.binding.owner),DriveAExportJournal.DATABASE).use{it.rawQuery("SELECT COUNT(*) FROM a_receipts WHERE kind='RESULT_OBSERVATION'",null).use{q->q.moveToFirst();assertEquals(0,q.getInt(0))}}};assertNull(Shadows.shadowOf(c.get()).nextStartedActivity);proof("U05")}
    @Test fun U06LargeFontKeepsScrollAndNextActionVisible(){RuntimeEnvironment.setFontScale(2f);val c=open();assertTrue(views(root(c.get())).any{it is android.widget.ScrollView});assertTrue(button(c.get(),"drive_a_prepare").isEnabled);render(c.get(),"U06-font200");c.pause().stop().destroy();proof("U06")}
    @Test fun U07CorruptSuccessLabelShowsReviewAndPreservesOriginalPayload(){val v=prepare().export!!;runBlocking(Dispatchers.IO){TextStoreTestSupport.open(AccountStorageContext(context,fake.binding.owner),DriveAExportJournal.DATABASE).use{it.execSQL("UPDATE a_exports SET state='RESULT_OBSERVATION_RECORDED'")}};val c=open();assertTrue(labels(c.get()).any{it.contains("저장 근거가 일치하지 않아 보류")});assertTrue(views(root(c.get())).none{it.tag=="drive_a_share"});runBlocking(Dispatchers.IO){TextStoreTestSupport.open(AccountStorageContext(context,fake.binding.owner),DriveAExportJournal.DATABASE).use{it.rawQuery("SELECT payload FROM a_exports",null).use{q->q.moveToFirst();assertEquals(v.payload,q.getString(0))}}};c.pause().stop().destroy();proof("U07")}
    @Test fun U08SyntheticApprovalCannotBeInferredFromRequestJson(){fake.claimedApproval=false;val r=prepare();assertNull(r.export);assertEquals("SOURCE_OR_TRANSPORT_HELD",r.state);assertEquals(0,fake.polls);proof("U08")}
    @Test fun U09ReadonlyProfileCannotFabricateNewV1Request(){val v=StrictJson.`object`(StrictJson.parse(fake.bytes)).toMutableMap();v["schemaVersion"]=1L;fake.requestBytes=StrictJson.bytes(v);val r=prepare();assertNull(r.export);assertEquals(0,fake.polls);proof("U09")}
}
