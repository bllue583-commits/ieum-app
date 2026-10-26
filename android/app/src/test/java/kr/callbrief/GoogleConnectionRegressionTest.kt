package kr.callbrief

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import kr.callagent.drive.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No Google SDK call, account, network socket or real phone audio is used in these host tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoogleConnectionRegressionTest : ProtectedTextHostTestBase() {
    internal class Memory : GoogleConnectionSession.Persistence {
        var saved=JSONObject();var fail=false
        override fun read()=JSONObject(saved.toString())
        override fun write(value:JSONObject) { check(!fail) { "STORE_FAILED" };saved=JSONObject(value.toString()) }
    }
    private lateinit var memory:Memory
    private lateinit var session:GoogleConnectionSession
    private lateinit var context:Context
    private var clock=1000L
    private val executor=Executors.newSingleThreadExecutor()
    private fun <T> io(action:()->T):T=executor.submit<T> { action() }.get(30,TimeUnit.SECONDS)
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();memory=Memory();session=GoogleConnectionSession(memory) { clock }
        GoogleConnectionSession.installForHost(session);RuntimeEnvironment.setFontScale(1f)
        context.getSharedPreferences("eum_onboarding_v1",Context.MODE_PRIVATE).edit().clear().commit()
        FakeActions.chosen=0;FakeActions.refresh=0;FakeActions.consent=0
    }
    @After fun cleanup() { GoogleConnectionSession.resetForHost();executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun activate(owner:String="owner_A",mail:String="synthetic.a@example.com") {
        val epoch=session.begin();session.activate(epoch,mail,mail,owner,"synthetic-memory-token")
    }
    private fun blocked(action:()->Unit) { try { action();fail("operation accepted") } catch(_:IllegalStateException) {} catch(_:IllegalArgumentException) {} }
    private fun evidence(id:String,vararg values:Pair<String,Any>) {
        val r=JSONObject().put("id",id).put("pass",true).put("scope","API35_HOST_SYNTHETIC_NOT_GOOGLE_DEVICE_OR_DOT")
        values.forEach { r.put(it.first,it.second) }
        val dir=File("build/reports/google-connection").apply { mkdirs() };File(dir,"$id.json").writeText(r.toString(2))
    }
    @Test fun G01AccessTokenNeverPersistedOrRestored() {
        activate();assertFalse(memory.saved.toString().contains("synthetic-memory-token"))
        val restored=GoogleConnectionSession(memory) { clock };assertNull(restored.accessToken());assertEquals(GoogleConnectionSession.Phase.REAUTH_REQUIRED,restored.view().phase)
        evidence("G01","token_persisted" to false)
    }
    @Test fun G02DurableEpochFailureDoesNotAcknowledgeSignOut() {
        activate();val old=session.epoch();memory.fail=true;blocked { session.localSignOut() }
        assertEquals(old,session.epoch());assertEquals(AuthorizationPort.State.ACTIVE,session.state());evidence("G02","fence_not_acknowledged" to true)
    }
    @Test fun G03SelectedAndObservedDifferentAccountsCannotActivate() {
        val e=session.begin();blocked { session.activate(e,"a@example.com","b@example.com","owner_A","token") };assertNull(session.accessToken());evidence("G03")
    }
    @Test fun G04LateSdkResultAfterSignOutCannotActivate() {
        val e=session.begin();session.localSignOut();blocked { session.activate(e,"a@example.com","a@example.com","owner_A","token") };assertEquals(AuthorizationPort.State.REVOKED,session.state());evidence("G04")
    }
    @Test fun G05SignOutCancelsInFlightAndClearsToken() {
        activate();var calls=0;session.registerCancellation(session.epoch(),{ calls++ });session.localSignOut();assertEquals(1,calls);assertNull(session.accessToken());assertEquals("",session.ownerBinding());evidence("G05")
    }
    @Test fun G06ConservativeMemoryLeaseRequiresRenewal() {
        activate();clock+=900_001;assertEquals(AuthorizationPort.State.TOKEN_EXPIRED,session.state());assertNull(session.accessToken());evidence("G06","google_expiry_asserted" to false)
    }
    @Test fun G07SameOwnerRenewalPreservesEpochButBlocksOldToken() {
        activate();val e=session.epoch();assertEquals(e,session.begin(true));assertNull(session.accessToken());session.activate(e,"synthetic.a@example.com","synthetic.a@example.com","owner_A","new-synthetic-token");assertEquals(e,session.epoch());evidence("G07")
    }
    @Test fun G08ResultCommitAfterEpochChangeIsBlocked() {
        activate();val e=session.epoch();session.begin();var writes=0;blocked { session.commit(e,"owner_A") { writes++ } };assertEquals(0,writes);evidence("G08")
    }
    @Test fun G09AccountDatabasesAndPreferenceNamespacesSeparateAtoBtoA() {
        val a=AccountStorageContext(context,"owner_A");val b=AccountStorageContext(context,"owner_B")
        a.openOrCreateDatabase("example.db",0,null).use { it.execSQL("CREATE TABLE notes(value TEXT)");it.execSQL("INSERT INTO notes VALUES('SYNTHETIC_NOTE_DONE_A')") }
        b.openOrCreateDatabase("example.db",0,null).use { it.execSQL("CREATE TABLE notes(value TEXT)");assertEquals(0L,it.rawQuery("SELECT COUNT(*) FROM notes",null).use { c->c.moveToFirst();c.getLong(0) }) }
        a.getSharedPreferences("ui",0).edit().putString("note","NOTE_A").commit();assertNull(b.getSharedPreferences("ui",0).getString("note",null))
        a.openOrCreateDatabase("example.db",0,null).use { assertEquals("SYNTHETIC_NOTE_DONE_A",it.rawQuery("SELECT value FROM notes",null).use { c->c.moveToFirst();c.getString(0) }) }
        assertNotEquals(a.getDatabasePath("example.db"),b.getDatabasePath("example.db"));evidence("G09","a_note_done_preserved" to true)
    }
    @Test fun G10PrivateFilesAndAudioRootsSeparateFromLegacyAndOtherAccount() {
        val a=AccountStorageContext(context,"owner_A");val b=AccountStorageContext(context,"owner_B")
        File(a.noBackupFilesDir,"generated.bin").writeText("ONLY_A");assertFalse(File(b.noBackupFilesDir,"generated.bin").exists());assertFalse(File(context.noBackupFilesDir,"generated.bin").exists());assertNotEquals(a.filesDir,b.filesDir);evidence("G10")
    }
    @Test fun G11SignOutPreservesPhysicalAccountDataAndDoesNotAssignLegacy() {
        activate();val a=AccountStorageContext(context,"owner_A");File(a.filesDir,"generated.bin").writeText("A")
        session.localSignOut();assertEquals(context,AccountStorageContext.selected(context));assertEquals("A",File(a.filesDir,"generated.bin").readText());evidence("G11","deleted" to false)
    }
    @Test fun G12VerifiedOwnerSelectsOwnNamespace() {
        activate();val selected=AccountStorageContext.selected(context) as AccountStorageContext;assertEquals("owner_A",selected.ownerId);evidence("G12")
    }
    private fun http(status:Int,body:String,onRequest:()->Unit={})=GoogleDriveHttp(session,OkHttpClient.Builder().addInterceptor { chain ->
        assertEquals("www.googleapis.com",chain.request().url.host);onRequest()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").body(body.toResponseBody()).build()
    }.build())
    @Test fun G13ActualHttpBridgeValidatesSelectedOwnerWithoutNetwork() {
        val e=session.begin();val owner=http(200,"{\"user\":{\"permissionId\":\"owner_A\",\"emailAddress\":\"synthetic.a@example.com\"}}")
            .verifySelectedOwner(e,"synthetic.a@example.com","token");assertEquals("owner_A",owner.permissionId);evidence("G13","network_calls" to 0)
    }
    @Test fun G14HttpOwnerMismatchRejects() {
        val e=session.begin();blocked { http(200,"{\"user\":{\"permissionId\":\"owner_B\",\"emailAddress\":\"b@example.com\"}}").verifySelectedOwner(e,"a@example.com","token") };evidence("G14")
    }
    @Test fun G15Http401InvalidatesMemoryToken() {
        activate();try { DriveRestTransport(session,http(401,"{}")).generateIds(4);fail() } catch(_:DriveTransport.DriveException) {}
        assertNull(session.accessToken());assertEquals(AuthorizationPort.State.TOKEN_EXPIRED,session.state());evidence("G15")
    }
    @Test fun G16LogoutDuringHttpResponsePreventsReturn() {
        activate();blocked { DriveRestTransport(session,http(200,"{\"ids\":[\"a\",\"b\",\"c\",\"d\"]}") { session.localSignOut() }).generateIds(4) };evidence("G16")
    }
    @Test fun G17HttpResponseBoundEnforced() {
        activate();blocked { DriveRestTransport(session,http(200,"x".repeat(StrictJson.MAX_BYTES+1))).generateIds(4) };evidence("G17")
    }
    @Test fun G18ProductionClientHasNoCookiesRedirectsOrAutomaticRetry() {
        val c=GoogleDriveHttp.client();assertFalse(c.followRedirects);assertFalse(c.followSslRedirects);assertFalse(c.retryOnConnectionFailure);assertSame(okhttp3.CookieJar.NO_COOKIES,c.cookieJar);evidence("G18")
    }
    class FakeDrive : DriveTransport {
        val specs=linkedMapOf<String,DriveTransport.FileSpec>();val bytes=linkedMapOf<String,ByteArray>();val versions=mutableMapOf<String,Int>();var counter=0;var downloads=0
        override fun generateIds(count:Int)=(0 until count).map { "synthetic_drive_"+(++counter) }
        override fun create(spec:DriveTransport.FileSpec,content:ByteArray):DriveTransport.Metadata { specs[spec.id]=spec;bytes[spec.id]=content.clone();versions[spec.id]=1;return metadata(spec.id) }
        override fun metadata(id:String):DriveTransport.Metadata { val s=specs[id] ?: throw DriveTransport.DriveException(DriveTransport.Failure.NOT_FOUND);return DriveTransport.Metadata(s.id,s.parentId,s.name,s.mimeType,s.ownerBinding,(versions[id] ?: 1).toString(),false,true,true,bytes.getValue(id).size.toLong()) }
        override fun download(id:String,max:Int):DriveTransport.Download { downloads++;return DriveTransport.Download(bytes.getValue(id)) }
        fun failure(result:String) { val r=StrictJson.`object`(StrictJson.parse(bytes.getValue(result))).toMutableMap();r["state"]="ERROR";r["resultRevision"]=1L;r["error"]=mapOf("code" to "SYNTHETIC_ERROR","message" to "test only");r["resultDigest"]=WireV0Codec.digest("result",r);bytes[result]=StrictJson.bytes(r);versions[result]=versions.getValue(result)+1 }
    }
    private fun db()=TextStoreTestSupport.open(AccountStorageContext(context,"owner_A"),GeneralLocalStore.DATABASE)
    private fun count(table:String)=db().use { it.rawQuery("SELECT COUNT(*) FROM $table",null).use { c->c.moveToFirst();c.getLong(0) } }
    @Test fun G19SubmitDuplicateReopenUsesOneJobAndIds() {
        activate();val d=FakeDrive();val id=io { GoogleRelayStore(context,session,d,postingCapability={true}).use { val r=it.submitSynthetic();assertEquals(DriveRelay.Outcome.SENT,r.outcome);r.jobId } }
        io { GoogleRelayStore(context,session,d,postingCapability={true}).use { val r=it.submitSynthetic();assertEquals(DriveRelay.Outcome.DUPLICATE,r.outcome);assertEquals(id,r.jobId) } };assertEquals(4,d.counter);assertEquals(1L,count("drive_v0_jobs"));evidence("G19")
    }
    @Test fun G20ErrorReceiptExplicitRetryCreatesNewJobAndRetainsFailure() {
        activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->
            val old=s.submitSynthetic().jobId;val before=s.list().single();d.failure(before.resultId);assertEquals("ERROR",s.pollReadOnly(old));assertEquals("ERROR",s.list().single().remoteState)
            assertEquals(old,s.submitSynthetic().jobId)
            val next=s.retryFailed(old);assertEquals(DriveRelay.Outcome.SENT,next.outcome);assertNotEquals(old,next.jobId)
            val rows=s.list();assertEquals("CANCELLED",rows.first { it.id==old }.phase);assertEquals(old,rows.first { it.id==next.jobId }.retryOf)
            assertNotEquals(before.resultId,rows.first { it.id==next.jobId }.resultId)
        } };assertEquals(1L,count("drive_profiles_receipts"));assertEquals(1L,count("eum_google_retries"));evidence("G20","failure_preserved" to true)
    }
    @Test fun G21RepeatedExplicitRetryCannotMakeThirdAttempt() {
        activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->val old=s.submitSynthetic().jobId;d.failure(s.list().single().resultId);s.pollReadOnly(old);val first=s.retryFailed(old);val second=s.retryFailed(old);assertEquals(first.jobId,second.jobId);assertEquals(DriveRelay.Outcome.DUPLICATE,second.outcome) } };assertEquals(2L,count("drive_v0_jobs"));evidence("G21")
    }
    @Test fun G22LateOldResultAfterRetryBlockedBeforeDownload() {
        activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->val old=s.submitSynthetic().jobId;d.failure(s.list().single().resultId);s.pollReadOnly(old);s.retryFailed(old);val before=d.downloads;assertEquals("CANCELLED",s.pollReadOnly(old));assertEquals(before,d.downloads) } };evidence("G22")
    }
    @Test fun G23ExplicitRetryBeforeFailureIsRejected() {
        activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->val id=s.submitSynthetic().jobId;blocked { s.retryFailed(id) };assertEquals("SENT",s.list().single().phase) } };assertEquals(0L,count("eum_google_retries"));evidence("G23")
    }
    @Test fun G24OtherAccountSeesNoJobsAndReturningAccountKeepsOwn() {
        activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { it.submitSynthetic() } };activate("owner_B","synthetic.b@example.com")
        io { GoogleRelayStore(context,session,d,postingCapability={true}).use { assertTrue(it.list().isEmpty()) } };activate()
        io { GoogleRelayStore(context,session,d,postingCapability={true}).use { assertEquals(1,it.list().size) } };evidence("G24")
    }
    @Test fun G25OldStoreCannotCommitOrListAfterAccountChange() {
        activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s -> s.submitSynthetic();activate("owner_B","b@example.com");blocked { s.list() } } };evidence("G25")
    }
    @Test fun G26ReceiptReplaceAndDeleteRejectedByActualSqlite() {
        activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->val id=s.submitSynthetic().jobId;d.failure(s.list().single().resultId);s.pollReadOnly(id) } }
        db().use { database ->
            try { database.execSQL("INSERT OR REPLACE INTO drive_profiles_receipts SELECT * FROM drive_profiles_receipts");fail() } catch(_:android.database.sqlite.SQLiteException) {}
            try { database.execSQL("DELETE FROM drive_profiles_receipts");fail() } catch(_:android.database.sqlite.SQLiteException) {}
        };assertEquals(1L,count("drive_profiles_receipts"));evidence("G26")
    }
    @Test fun G27LegacyNativeNoteDoneUnaffectedByAccountRelay() {
        val keys=SyntheticPilotIntegrationTest.HostKeys();io { GeneralLocalStoreTest.host(context,keys).use { s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply { put() });val row=s.snapshot().getJSONObject(0);s.edit(row.getString("file_id"),row,"SYNTHETIC_LEGACY_NOTE","done") } }
        val before=io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot().toString() } };activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { it.submitSynthetic() } };session.localSignOut()
        assertEquals(before,io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot().toString() } });evidence("G27")
    }
    internal class FakeActions : GoogleConnectActions {
        companion object { var chosen=0;var refresh=0;var consent=0 }
        override fun chooseAccount() { chosen++ };override fun refreshExistingNoPrompt() { refresh++ };override fun requestDriveConsent() { consent++ };override fun close() {}
    }
    internal class HostConnections : ConnectionCenterActivity() { override fun createAuthorization(s:GoogleConnectionSession,c:GoogleAuthorizationSdk.Consumer)=FakeActions() }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun connections(onboarding:Boolean=false)=Robolectric.buildActivity(HostConnections::class.java,Intent(context,HostConnections::class.java).putExtra("onboarding",onboarding)).setup()
    private fun screen(a:ConnectionCenterActivity,name:String) {
        val root=a.findViewById<View>(android.R.id.content);root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));root.layout(0,0,720,1600)
        val bitmap=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap));val dir=File("build/reports/google-connection-screens").apply { mkdirs() };FileOutputStream(File(dir,"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun G28FirstConnectHasNoAutomaticConsentOrAccountPicker() {
        val c=connections(true);assertEquals(0,FakeActions.chosen);assertEquals(0,FakeActions.consent);assertTrue(c.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE !=0);screen(c.get(),"first-connect-100");c.pause().stop().destroy();evidence("G28")
    }
    @Test fun G29RestartRequestsSilentRefreshWithoutConsentWindow() {
        activate();session=GoogleConnectionSession(memory) { clock };GoogleConnectionSession.installForHost(session);val c=connections();assertEquals(1,FakeActions.refresh);assertEquals(0,FakeActions.consent);assertEquals(0,FakeActions.chosen);c.pause().stop().destroy();evidence("G29")
    }
    @Test fun G30SkipOnboardingPersistsOnce() {
        val c=connections(true);val root=c.get().findViewById<View>(android.R.id.content);views(root).filterIsInstance<Button>().single { it.text.toString()=="지금은 건너뛰고 시작" }.performClick()
        assertTrue(context.getSharedPreferences("eum_onboarding_v1",0).getBoolean("seen",false));c.pause().stop().destroy();evidence("G30")
    }
    @Test fun G31DisconnectedNaverAndKakaoNeverClaimConnected() {
        val c=connections();val labels=views(c.get().findViewById(android.R.id.content)).filterIsInstance<TextView>().map { it.text.toString() };assertTrue(labels.contains("네이버 메일 새 연결 · 미연결"));assertTrue(labels.contains("카카오 연결 · 준비 중"));c.pause().stop().destroy();evidence("G31")
    }
    @Test fun G32LargeFontKeepsExplicitAccountChoiceAndProtectedDialog() {
        RuntimeEnvironment.setFontScale(2f);val c=connections();val root=c.get().findViewById<View>(android.R.id.content);views(root).filterIsInstance<Button>().single { it.text.toString()=="카카오 연결 · 준비 중" }.performClick()
        val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE !=0)
        screen(c.get(),"first-connect-200");c.pause().stop().destroy();evidence("G32","font_scale" to 2)
    }
    @Test fun G33NaverEntryHasDeniedRealAccountPolicy() {
        assertFalse(kr.callbrief.navermail.android.BuildConfig.REAL_ACCOUNT_ACTIONS_APPROVED)
        val c=connections();views(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>().single { it.tag=="naver_mail_settings" }.performClick()
        assertEquals(ScopedNaverMailSettingsActivity::class.java.name,shadowOf(c.get()).nextStartedActivity.component!!.className)
        c.pause().stop().destroy();evidence("G33","actual_mail_access" to 0)
    }
    @Test fun G34NaverWrapperRejectsPriorOwnerOnResume() {
        activate();val c=Robolectric.buildActivity(ScopedNaverMailSettingsActivity::class.java).setup();c.pause().stop();activate("owner_B","b@example.com");c.restart().resume();assertTrue(c.get().isFinishing);c.pause().stop().destroy();evidence("G34")
    }
    private fun mainField(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun settleMain(a:MainActivity) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(mainField(a,"catalogLoading")==false)return;Thread.sleep(10) };fail("MAIN_NOT_SETTLED") }
    private fun seed(owner:String,note:String) {
        val scoped=AccountStorageContext(context,owner);val keys=SyntheticPilotIntegrationTest.HostKeys()
        io { GeneralLocalStoreTest.host(scoped,keys).use { s ->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply { put() });val row=s.snapshot().getJSONObject(0);s.edit(row.getString("file_id"),row,note,"done") } }
    }
    @Test fun G35MainAccountSwitchClearsCacheAndPreservesReturningDone() {
        seed("owner_A","SYNTHETIC_NOTE_A");seed("owner_B","SYNTHETIC_NOTE_B");activate()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();settleMain(c.get())
        fun note()=(mainField(c.get(),"catalogFiles") as org.json.JSONArray).getJSONObject(0).getString("manual_note")
        assertEquals("SYNTHETIC_NOTE_A",note());c.pause().stop();activate("owner_B","b@example.com");c.restart().resume();settleMain(c.get());assertEquals("SYNTHETIC_NOTE_B",note())
        c.pause().stop();activate();c.restart().resume();settleMain(c.get());assertEquals("SYNTHETIC_NOTE_A",note());assertEquals("done",(mainField(c.get(),"catalogFiles") as org.json.JSONArray).getJSONObject(0).getString("manual_status"))
        c.pause().stop().destroy();evidence("G35","a_b_a" to true)
    }
    @Test fun G36SavedUiOfDifferentOwnerIsNotReused() {
        activate();val a=AccountStorageContext(context,"owner_A");a.getSharedPreferences("local_ui_v2",0).edit().putInt("tab",1).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();settleMain(c.get());val bundle=android.os.Bundle();c.saveInstanceState(bundle).pause().stop().destroy();activate("owner_B","b@example.com")
        val next=Robolectric.buildActivity(MainActivity::class.java).create(bundle).start().resume().visible();settleMain(next.get());assertEquals(0,mainField(next.get(),"tab"));next.pause().stop().destroy();evidence("G36")
    }
    @Test fun G37MainLauncherShowsFirstConnectOnceAfterIntro() {
        val intent=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val first=Robolectric.buildActivity(MainActivity::class.java,intent).setup();settleMain(first.get());shadowOf(Looper.getMainLooper()).idleFor(1500,TimeUnit.MILLISECONDS)
        assertEquals(ConnectionCenterActivity::class.java.name,shadowOf(first.get()).nextStartedActivity.component!!.className);first.pause().stop().destroy()
        context.getSharedPreferences("eum_onboarding_v1",0).edit().putBoolean("seen",true).commit()
        val again=Robolectric.buildActivity(MainActivity::class.java,intent).setup();settleMain(again.get());shadowOf(Looper.getMainLooper()).idleFor(1500,TimeUnit.MILLISECONDS)
        assertNull(shadowOf(again.get()).nextStartedActivity);again.pause().stop().destroy();evidence("G37")
    }
    @Test fun G38AccountVaultAliasesAndSettingsCipherCannotCross() {
        val a=AccountStorageContext(context,"owner_A");val b=AccountStorageContext(context,"owner_B");val keys=SyntheticPilotIntegrationTest.HostKeys()
        io { GeneralLocalStoreTest.host(a,keys).use { it.initializeEmpty() };GeneralLocalStoreTest.host(b,SyntheticPilotIntegrationTest.HostKeys()).use { it.initializeEmpty() } }
        assertNotEquals(a.noBackupFilesDir,b.noBackupFilesDir)
        val key=javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val access=object:SecureSettings.KeyAccess { override fun existing()=key;override fun createForFirstEmptyStore()=key }
        SecureSettings(a,access).update { it.put("memo","SYNTHETIC_A") };assertFalse(SecureSettings(b,access).snapshot().has("memo"));evidence("G38","actual_keystore" to false)
    }
    @Test fun G39DriveFolderActualRootParentDoesNotBreakResultPoll() {
        activate();val base=FakeDrive();val d=object:DriveTransport by base {
            override fun metadata(id:String):DriveTransport.Metadata { val m=base.metadata(id);return if(m.mimeType==DriveTransport.FOLDER_MIME)DriveTransport.Metadata(m.id,"synthetic_my_drive_root",m.name,m.mimeType,m.ownerBinding,m.version,m.trashed,m.isAppAuthorized,m.privateToOwner,m.size) else m }
        }
        io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s->val id=s.submitSynthetic().jobId;assertEquals("PENDING",s.pollReadOnly(id)) } };evidence("G39")
    }
    @Test fun G40SharedWireAcceptsIndependentAccountButPreservesExactOwnerBinding() {
        activate("owner_B","synthetic.b@example.com");val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s->assertEquals(DriveRelay.Outcome.SENT,s.submitSynthetic().outcome) } }
        val r=d.bytes.values.mapNotNull { bytes->runCatching { StrictJson.`object`(StrictJson.parse(bytes)) }.getOrNull() }.single { it["kind"]=="CALL_ANALYSIS_REQUEST" }
        assertEquals("synthetic.b@example.com",StrictJson.`object`(r["ownerBinding"])["driveAccount"]);assertEquals("owner_B",StrictJson.`object`(r["ownerBinding"])["ownerId"]);evidence("G40")
    }
    @Test fun G41SelectedSyntheticWavAtoBtoAKeepsAllVersions() {
        activate();val d=FakeDrive();val uri=android.net.Uri.parse("content://synthetic-provider/generated.wav")
        val shadow=shadowOf(context.contentResolver)
        var wav=GeneralLocalStoreTest.wav();shadow.registerInputStreamSupplier(uri) { java.io.ByteArrayInputStream(wav) }
        io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->s.submitSelectedSynthetic(uri,true);s.submitSelectedSynthetic(uri,true);wav=GeneralLocalStoreTest.wav(true);s.submitSelectedSynthetic(uri,true);wav=GeneralLocalStoreTest.wav();s.submitSelectedSynthetic(uri,true) } }
        assertEquals(3L,count("eum_google_versions"));assertEquals(3L,count("drive_v0_jobs"));evidence("G41","versions" to 3)
    }
    @Test fun G42SelectedWavRequiresExplicitSyntheticConsentBeforeRead() {
        activate();val d=FakeDrive();val uri=android.net.Uri.parse("content://synthetic-provider/generated.wav");var opens=0
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { opens++;java.io.ByteArrayInputStream(GeneralLocalStoreTest.wav()) }
        io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->blocked { s.submitSelectedSynthetic(uri,false) } } };assertEquals(0,opens);assertEquals(0,d.counter);evidence("G42")
    }
    @Test fun G43ChangedSelectedAudioCannotRetryOldFailure() {
        activate();val d=FakeDrive();val uri=android.net.Uri.parse("content://synthetic-provider/generated.wav");var wav=GeneralLocalStoreTest.wav()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { java.io.ByteArrayInputStream(wav) }
        io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->val id=s.submitSelectedSynthetic(uri,true).jobId;d.failure(s.list().single().resultId);s.pollReadOnly(id);wav=GeneralLocalStoreTest.wav(true);blocked { s.retryFailed(id) };assertEquals("SENT",s.list().single().phase) } };assertEquals(0L,count("eum_google_retries"));evidence("G43")
    }
    @Test fun G44ReadOnlyFixtureProposalPreservesCanonicalNoteDoneAndExecutionUnverified() {
        seed("owner_A","SYNTHETIC_NATIVE_DONE");val scoped=AccountStorageContext(context,"owner_A");val before=io { GeneralLocalStore.catalog(scoped).listFiles().toString() }
        activate();val d=FakeDrive();io { GoogleRelayStore(context,session,d,postingCapability={true}).use { s ->val id=s.submitSynthetic().jobId;val file=s.list().single().resultId
            val r=StrictJson.`object`(StrictJson.parse(d.bytes.getValue(file))).toMutableMap();r["state"]="PROPOSAL";r["resultRevision"]=1L;r["stt"]=mapOf("execution" to "FIXTURE","text" to "합성 전사 예시","textSha" to DriveRelay.sha256("합성 전사 예시".toByteArray()),"evidenceRef" to "synthetic-fixture-only")
            r["resultDigest"]=WireV0Codec.digest("result",r);d.bytes[file]=StrictJson.bytes(r);d.versions[file]=2
            assertEquals("LEGACY_CANDIDATE",s.pollReadOnly(id));assertEquals("합성 전사 예시",s.list().single().rawText)
        } };assertEquals(before,io { GeneralLocalStore.catalog(scoped).listFiles().toString() });evidence("G44","canonical_changes" to 0,"real_dot_execution" to false)
    }
    @Test fun G45GooglePostBodyIsOneShotAndTokenOmittedFromRequestString() {
        activate();var once=false
        val client=OkHttpClient.Builder().addInterceptor { chain ->once=chain.request().body!!.isOneShot();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("synthetic").body("{}".toResponseBody()).header("Retry-After","0").build() }.build()
        val drive=DriveRestTransport(session,GoogleDriveHttp(session,client))
        try { drive.create(DriveTransport.FileSpec("synthetic_folder",null,"test",DriveTransport.FOLDER_MIME,"owner_A",DriveTransport.Role.PRIVATE_FOLDER),byteArrayOf());fail() } catch(_:DriveTransport.DriveException) {}
        assertTrue(once);evidence("G45","one_shot" to true,"network_calls" to 0)
    }
    @Test fun G46KakaoSettingsRemainDisconnectedAndNotLive() {
        val config=kr.callbrief.kakao.android.KakaoConfiguration.fromResources(context);assertFalse(config.canLogin);assertFalse(config.liveSendEnabled)
        val c=Robolectric.buildActivity(ScopedKakaoSettingsActivity::class.java).setup();assertTrue(c.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0);c.pause().stop().destroy();evidence("G46")
    }
    @Test fun G47KakaoSettingsCloseOnOwnerEpochChange() {
        activate();val c=Robolectric.buildActivity(ScopedKakaoSettingsActivity::class.java).setup();c.pause().stop();activate("owner_B","b@example.com");c.restart().resume();assertTrue(c.get().isFinishing);c.pause().stop().destroy();evidence("G47")
    }
    internal class DelayedConnections : ConnectionCenterActivity() {
        companion object {
            lateinit var entered:java.util.concurrent.CountDownLatch
            lateinit var release:java.util.concurrent.CountDownLatch
            lateinit var finished:java.util.concurrent.CountDownLatch
            val drive=FakeDrive()
        }
        override fun createAuthorization(s:GoogleConnectionSession,c:GoogleAuthorizationSdk.Consumer)=FakeActions()
        override fun createRelay(s:GoogleConnectionSession):GoogleRelayStore {
            entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            try { return GoogleRelayStore(this,s,drive,postingCapability={true}) } finally { finished.countDown() }
        }
    }
    @Test fun G48StopBeforeRelayCreationBlocksIoAndLateUi() {
        activate();DelayedConnections.entered=java.util.concurrent.CountDownLatch(1);DelayedConnections.release=java.util.concurrent.CountDownLatch(1);DelayedConnections.finished=java.util.concurrent.CountDownLatch(1)
        val c=Robolectric.buildActivity(DelayedConnections::class.java).setup();assertTrue(DelayedConnections.entered.await(5,TimeUnit.SECONDS));c.pause().stop();DelayedConnections.release.countDown();assertTrue(DelayedConnections.finished.await(5,TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle();assertEquals(AuthorizationPort.State.TOKEN_EXPIRED,session.state());assertEquals(0,DelayedConnections.drive.counter)
        assertFalse(AccountStorageContext(context,"owner_A").getDatabasePath(GeneralLocalStore.DATABASE).exists());c.destroy();evidence("G48","late_raw_open" to 0,"late_relay_admission" to 0)
    }
}
