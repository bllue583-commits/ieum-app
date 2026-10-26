package kr.callbrief

import android.content.*
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.*
import kotlinx.coroutines.*
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.ieum.discovery.DiscoveryTypes.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual discovery Worker/Watch + recovery + protected SQL/native request/coordinator.
 * SDK, selected ContentProvider, vault keys and Drive are public synthetic boundaries only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class,AutomaticDiscoveryRecoveryIntegrationTest.SettingsKeys::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AutomaticDiscoveryRecoveryIntegrationTest:ProtectedTextHostTestBase() {
    @org.robolectric.annotation.Implements(className="kr.callbrief.SecureSettings\$AndroidKeys",isInAndroidSdk=false)
    class SettingsKeys {
        @org.robolectric.annotation.Implementation fun existing():javax.crypto.SecretKey=javax.crypto.spec.SecretKeySpec(ByteArray(32){0x30},"AES")
        @org.robolectric.annotation.Implementation fun createForFirstEmptyStore():javax.crypto.SecretKey=existing()
    }
    private class Memory:GoogleConnectionSession.Persistence {
        var saved=JSONObject()
        override fun read()=JSONObject(saved.toString())
        override fun write(value:JSONObject){saved=JSONObject(value.toString())}
    }
    class Provider:ContentProvider() {
        val documents=linkedMapOf<String,SourceDocument>();var queries=0;var opens=0
        var beforeQuery:()->Unit={}
        override fun onCreate()=true
        override fun call(method:String,arg:String?,extras:android.os.Bundle?)=SyntheticTreeMembershipSupport.response(method,extras,documents.keys)
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):android.database.Cursor {
            queries++;beforeQuery()
            val columns=checkNotNull(projection);val cursor=MatrixCursor(columns)
            val rows=if(uri.pathSegments.contains("children"))documents.values else listOfNotNull(documents[DocumentsContract.getDocumentId(uri)])
            for(d in rows)cursor.addRow(columns.map{when(it){
                DocumentsContract.Document.COLUMN_DOCUMENT_ID->d.documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME->d.name
                DocumentsContract.Document.COLUMN_MIME_TYPE->d.mime
                DocumentsContract.Document.COLUMN_SIZE->d.size
                DocumentsContract.Document.COLUMN_LAST_MODIFIED->d.modified
                DocumentsContract.Document.COLUMN_FLAGS->0
                else->null
            }}.toTypedArray())
            return cursor
        }
        override fun openFile(uri:Uri,mode:String):android.os.ParcelFileDescriptor {opens++;error("NO_REAL_AUDIO_PROVIDER_READ")}
        override fun getType(uri:Uri)="audio/wav"
        override fun insert(uri:Uri,v:ContentValues?):Uri?=error("READ_ONLY")
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?):Int=error("READ_ONLY")
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("READ_ONLY")
    }
    private lateinit var context:Context;private lateinit var scoped:AccountStorageContext
    private lateinit var memory:Memory;private lateinit var session:GoogleConnectionSession
    private lateinit var source:GeneralLocalStoreTest.Source;private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var provider:Provider;private lateinit var settings:SecureSettings
    private var now=1000L;private var elapsed=0L;private var localOpens=0
    private val owner="PUBLIC_DISCOVERY_OWNER";private val account="discovery@example.invalid"
    private val work=mutableListOf<Pair<String,PeriodicWorkRequest>>()
    private val fixtureKey=javax.crypto.spec.SecretKeySpec(ByteArray(32){0x30},"AES")
    private val watches=mutableListOf<AutomaticDiscoveryWatch>()
    private fun settings(c:Context)=SecureSettings(c,object:SecureSettings.KeyAccess {
        override fun existing()=fixtureKey;override fun createForFirstEmptyStore()=fixtureKey
    })
    @Before fun setup()=runBlocking(Dispatchers.IO) {
        context=RuntimeEnvironment.getApplication();memory=Memory();session=GoogleConnectionSession(memory){now}
        android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,1)
        val epoch=session.begin();session.activate(epoch,account,account,owner,"PUBLIC_TEST_ONLY_RAM_TOKEN")
        GoogleConnectionSession.installForHost(session);scoped=AccountStorageContext(context,owner)
        settings=settings(scoped);source=GeneralLocalStoreTest.Source().apply{put()};keys=SyntheticPilotIntegrationTest.HostKeys()
        settings.update{it.put("treeUri",source.rootUri)}
        context.contentResolver.takePersistableUriPermission(Uri.parse(source.rootUri),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        provider=Provider().apply{documents.putAll(source.documents)}
        provider.attachInfo(context,ProviderInfo().apply{authority="generated-fixture";grantUriPermissions=true})
        ShadowContentResolver.registerProviderInternal("generated-fixture",provider)
        GeneralLocalStoreTest.host(scoped,keys).use{it.initializeEmpty()}
        ScheduledDotProofRegistry.plan={_,_->ScheduledDotProofPlan()}
        ScheduledDotProofRuntime.installWorkForHost{_,_->error("NO_SCHEDULED_PLAN")}
        AutomaticResultReconciliation.installForHost(object:AutomaticResultWorkRegistry{override fun once(n:String,r:OneTimeWorkRequest){}})
        installLocalBoundary()
    }
    @After fun cleanup() {
        watches.forEach{it.close()};AutomaticRecordingDiscovery.installForHost();ExistingGoogleSessionRecoveryRuntime.installForHost(null)
        AutomaticResultReconciliation.installForHost();ScheduledDotProofRuntime.installWorkForHost(null);ScheduledDotProofRegistry.plan=null
        AutomationProviderRegistry.create=null;GoogleConnectionSession.resetForHost()
    }
    private fun local(c:Context):NativeDiscoverySession {
        val store=GeneralLocalStoreTest.host(c,keys)
        return NativeDiscoverySession(store,source,checkNotNull(AutomaticRecordingDiscovery.selection(c,settings(c).snapshot())),
            {settings(c).snapshot()},kr.co.ieum.discovery.DiscoveryTypes.Clock{Moment("PUBLIC_DISCOVERY_BOOT",elapsed)},{GeneralLocalStoreTest.NOW},true,
            kr.co.ieum.discovery.DiscoveryTypes.Config(10,10000,1000,25000000,2))
    }
    private fun installLocalBoundary()=AutomaticRecordingDiscovery.installForHost(store={c->localOpens++;GeneralLocalStoreTest.host(c,keys)},work={n,r->work+=n to r})
    private fun restart(){session=GoogleConnectionSession(memory){now};GoogleConnectionSession.installForHost(session)}
    private fun binding()=checkNotNull(AutomaticRecordingDiscovery.capture(scoped))
    private fun params(input:Data=binding().data(),attempt:Int=0):WorkerParameters {
        val executor=java.util.concurrent.Executor{it.run()}
        return WorkerParameters(UUID.randomUUID(),input,emptyList(),WorkerParameters.RuntimeExtras(),attempt,0,executor,Dispatchers.Unconfined,
            androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor(executor),object:WorkerFactory(){override fun createWorker(c:Context,n:String,p:WorkerParameters):ListenableWorker?=null},
            ProgressUpdater{_,_,_->error("unused")},ForegroundUpdater{_,_,_->error("unused")})
    }
    private class Auth(private val account:String,private val owner:String):ExistingGoogleAuthorization {
        val sdk=AtomicInteger();val owners=AtomicInteger();var resolution=false
        var before:()->Unit={};var verifying:()->Unit={}
        override fun existingOnly(a:String):ExistingAuthorizationResult {
            assertEquals(account,a);sdk.incrementAndGet();before();return ExistingAuthorizationResult(resolution,if(resolution)null else "PUBLIC_REFRESHED_RAM_TOKEN")
        }
        override fun verifyOwner(e:Long,a:String,t:String):GoogleDriveHttp.Owner {owners.incrementAndGet();verifying();return GoogleDriveHttp.Owner(owner,account)}
    }
    private fun auth(owner:String=this.owner,account:String=this.account)=Auth(account,owner).also{ExistingGoogleSessionRecoveryRuntime.installForHost{_,_->it}}
    private fun run(input:Data=binding().data(),attempt:Int=0)=AutomaticRecordingDiscoveryWorker(context,params(input,attempt)).doWork()
    private fun state(result:ListenableWorker.Result)=when(result){
        is ListenableWorker.Result.Success->result.outputData.getString("automation_state")
        is ListenableWorker.Result.Failure->result.outputData.getString("automation_state")
        else->"RETRY"
    }
    private fun noSource(){assertEquals(0,localOpens);assertEquals(0,provider.queries);assertEquals(0,provider.opens);assertEquals(0,source.opens)}
    private fun db()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun proof(id:String,extra:JSONObject=JSONObject()) {
        val file=File("build/reports/discovery-recovery/$id.json");file.parentFile.mkdirs()
        file.writeText(extra.put("id",id).put("pass",true).put("executionScope","API35_HOST_SYNTHETIC_SDK_SAF_DRIVE_REAL_WORKER_SQL_JCE")
            .put("actualGms",false).put("actualExternalTransfers",0).put("actualModelCalls",0).put("actualKakaoSends",0).toString(2))
    }

    @Test fun D01PeriodicInputPinsExistingScopeAndMigratesOwnerOnlyInputWithoutToken()=runBlocking(Dispatchers.IO) {
        val old=workDataOf("local_owner" to owner);val sdk=auth();restart()
        assertEquals("WAITING_DISCOVERY_BINDING",state(run(old)));noSource();assertEquals(0,sdk.sdk.get())
        AutomaticRecordingDiscovery.ensure(scoped);val first=work.single()
        assertEquals(binding(),AutomaticRecordingDiscovery.binding(first.second.workSpec.input));assertEquals(900000L,first.second.workSpec.intervalDuration)
        assertFalse(first.second.workSpec.input.toString().contains("TOKEN"));assertFalse(first.second.workSpec.input.toString().contains("example.invalid"))
        settings.update{it.put("automaticDiscoverySelectionRevision",2)};AutomaticRecordingDiscovery.ensure(scoped)
        assertEquals(first.first,work.last().first);assertNotEquals(first.second.workSpec.input,work.last().second.workSpec.input)
        assertEquals("HELD_SOURCE",state(run(first.second.workSpec.input)));assertEquals(0,sdk.sdk.get());noSource()
        proof("D01",JSONObject().put("legacyInputCannotAcquireNewFence",true).put("samePeriodicName",true))
    }
    @Test fun D02ExpiredTokenActualWorkerRefreshesOnceAndKeepsNativeScope()=runBlocking(Dispatchers.IO) {
        val b=binding();val sid=memory.saved.getString("sessionId");now+=900001;val sdk=auth()
        assertEquals("WAITING_AUTHORIZED_SOURCE",state(run(b.data())));assertEquals(1,sdk.sdk.get());assertEquals(1,sdk.owners.get())
        assertEquals(b,binding());assertEquals(sid,memory.saved.getString("sessionId"));assertEquals(1,localOpens)
        run(b.data());assertEquals(1,sdk.sdk.get());assertFalse(memory.saved.toString().contains("RAM_TOKEN"))
        proof("D02",JSONObject().put("refreshes",1).put("scopeSessionEpochPreserved",true))
    }
    @Test fun D03ColdRestartActualWorkerRestoresOnlyPreviouslyVerifiedAccount()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();assertNull(session.accessToken());val sdk=auth()
        assertEquals("WAITING_AUTHORIZED_SOURCE",state(run(b.data())));assertEquals(b,binding());assertEquals(1,sdk.sdk.get());assertEquals(1,sdk.owners.get())
        proof("D03")
    }
    @Test fun D04ResolutionRequiresUserActionWithoutSourceOrRepeatedSdk()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth().apply{resolution=true}
        repeat(2){assertEquals("WAITING_USER_REAUTH",state(run(b.data())))}
        noSource();assertEquals(1,sdk.sdk.get());assertEquals(0,sdk.owners.get());assertNull(session.accessToken());proof("D04")
    }
    @Test fun D05WrongOwnerAndAccountNeverAdoptIdentityOrReadSource()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth(owner="PUBLIC_OTHER_OWNER")
        assertEquals("WAITING_USER_REAUTH",state(run(b.data())));noSource();assertNull(session.accessToken());assertEquals(owner,session.ownerBinding())
        restart();var checks=0
        ExistingGoogleSessionRecoveryRuntime.installForHost{_,_->object:ExistingGoogleAuthorization {
            override fun existingOnly(a:String)=ExistingAuthorizationResult(false,"PUBLIC_TOKEN")
            override fun verifyOwner(e:Long,a:String,t:String)=GoogleDriveHttp.Owner(owner,"other@example.invalid").also{checks++}
        }}
        assertEquals("WAITING_USER_REAUTH",state(run(b.data())));assertEquals(1,checks);assertEquals(1,sdk.sdk.get());noSource();proof("D05")
    }
    @Test fun D06MissingGmsIsExplicitWaitWithNoSourceAndNoNewGrant()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth().apply{before={throw GoogleAuthorizationEnvironmentMissing()}}
        assertEquals("WAITING_GOOGLE_ENVIRONMENT",state(run(b.data())));noSource();assertEquals(0,sdk.owners.get());assertNull(session.accessToken());proof("D06")
    }
    @Test fun D07TransientAttemptsZeroThroughFiveRetrySixBlocksBeforeSdkAfterRestart()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth().apply{before={throw java.io.IOException("PUBLIC_TRANSIENT")}}
        for(attempt in 0..5){assertEquals("RETRY",state(run(b.data(),attempt)));restart()}
        assertEquals("DISCOVERY_CHECK_RETRY_LIMIT",state(run(b.data(),6)));assertEquals(6,sdk.sdk.get());noSource();proof("D07",JSONObject().put("attemptIndices", "0..5").put("attempt6SdkCalls",0))
    }
    @Test fun D08LogoutAndChangedEpochBeforeWorkRejectOldBindingWithoutSdk()=runBlocking(Dispatchers.IO) {
        val b=binding();val sdk=auth();session.localSignOut();restart()
        assertEquals("STALE_NATIVE_SCOPE",state(run(b.data())));assertEquals(0,sdk.sdk.get());noSource()
        val e=session.begin();session.activate(e,account,account,owner,"PUBLIC_NEW_RAM_TOKEN")
        assertEquals("STALE_NATIVE_SCOPE",state(run(b.data())));assertEquals(0,sdk.sdk.get());noSource();proof("D08")
    }
    @Test fun D09AccountChangeDuringRecoveryDiscardsLateSuccess()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth().apply{before={val e=session.begin();session.activate(e,"other@example.invalid","other@example.invalid","PUBLIC_OTHER_OWNER","PUBLIC_OTHER_RAM_TOKEN")}}
        assertEquals("STALE_NATIVE_SCOPE",state(run(b.data())));noSource();assertEquals(0,sdk.owners.get());assertEquals("PUBLIC_OTHER_OWNER",session.ownerBinding());proof("D09")
    }
    @Test fun D10LogoutDuringOwnerReadbackNeverReactivatesOldEpoch()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();auth().apply{verifying={session.localSignOut()}}
        assertEquals("STALE_NATIVE_SCOPE",state(run(b.data())));noSource();assertNull(session.accessToken());assertEquals("",session.ownerBinding());proof("D10")
    }
    @Test fun D11StopDuringSdkAndAfterReadbackPreventsActivationAndLocalWork()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth();val worker=AutomaticRecordingDiscoveryWorker(context,params(b.data()))
        sdk.before={worker.stop(1)}
        assertEquals("LOCAL_WORK_STOPPED",state(worker.doWork()));noSource();assertEquals(0,sdk.owners.get());assertNull(session.accessToken())
        restart();val second=AutomaticRecordingDiscoveryWorker(context,params(b.data()));sdk.before={};sdk.verifying={second.stop(1)}
        assertEquals("LOCAL_WORK_STOPPED",state(second.doWork()));noSource();assertNull(session.accessToken());proof("D11")
    }
    @Test fun D12PolicyAndApprovalWithdrawalDuringRecoveryPreventSourceAccess()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth().apply{before={settings.update{it.put("pendingRevocation",true)}}}
        assertEquals("HELD_SOURCE",state(run(b.data())));noSource();assertEquals(0,sdk.owners.get());assertNull(session.accessToken());proof("D12")
    }
    @Test fun D13ReadGrantRevokedDuringRecoveryAndSelectionChangeBlockBeforeOwnerReadback()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth().apply{before={context.contentResolver.releasePersistableUriPermission(Uri.parse(source.rootUri),Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
        assertEquals("HELD_SOURCE",state(run(b.data())));noSource();assertEquals(0,sdk.owners.get())
        context.contentResolver.takePersistableUriPermission(Uri.parse(source.rootUri),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        sdk.before={settings.update{it.put("automaticDiscoverySelectionRevision",2)}}
        assertEquals("HELD_SOURCE",state(run(b.data())));noSource();assertEquals(0,sdk.owners.get());proof("D13")
    }
    @Test fun D14StoppedWorkerNeverRequestsSdkAndDoesNotRetry()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth();val worker=AutomaticRecordingDiscoveryWorker(context,params(b.data()));worker.stop(1)
        assertEquals("LOCAL_WORK_STOPPED",state(worker.doWork()));assertEquals(0,sdk.sdk.get());noSource();proof("D14")
    }
    @Test fun D15WatchAndWorkerShareSingleFlightButNeverAnotherOwnerResult()=runBlocking(Dispatchers.IO) {
        val b=binding();restart();val sdk=auth();val entered=CountDownLatch(1);val release=CountDownLatch(1);val observed=CountDownLatch(1)
        sdk.before={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val watch=AutomaticDiscoveryWatch(scoped){if(it!=null)observed.countDown()}.also{watches+=it}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {
            assertEquals("DISCOVERY_ALREADY_RUNNING",state(run(b.data())))
            val other=AccountStorageContext(context,"PUBLIC_OTHER_OWNER")
            assertEquals("STALE_NATIVE_SCOPE",AutomaticRecordingDiscovery.cycle(other,b,{false}).getString("state"))
            assertEquals(1,sdk.sdk.get());assertEquals(0,localOpens)
        }finally{release.countDown()}
        assertTrue(observed.await(5,TimeUnit.SECONDS));watch.close();assertEquals(1,sdk.sdk.get());assertEquals(1,localOpens);proof("D15")
    }
    @Test fun D16WatchCloseDuringSdkNeverContinuesToLocalOrRuntime()=runBlocking(Dispatchers.IO) {
        restart();val sdk=auth();val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        sdk.before={entered.countDown();try{release.await(5,TimeUnit.SECONDS)}finally{finished.countDown()}}
        val callbacks=AtomicInteger();val watch=AutomaticDiscoveryWatch(scoped){callbacks.incrementAndGet()}.also{watches+=it}
        assertTrue(entered.await(5,TimeUnit.SECONDS));watch.close();release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS))
        noSource();assertEquals(0,callbacks.get());assertNull(session.accessToken());proof("D16")
    }
    @Test fun D17RuntimeRejectsScopeAndStopBeforeScheduledResolution()=runBlocking(Dispatchers.IO) {
        val b=binding();var scheduledCalls=0;ScheduledDotProofRegistry.plan={_,_->scheduledCalls++;error("NO_SCHEDULED_IO")}
        assertEquals("STALE_NATIVE_SCOPE",AndroidAutomationRuntime.reconcileDiscovery(context,owner,b.epoch,"PUBLIC_STALE_SCOPE").getString("state"))
        assertEquals("LOCAL_WORK_STOPPED",AndroidAutomationRuntime.reconcileDiscovery(context,owner,b.epoch,b.nativeScope){true}.getString("state"))
        assertEquals(0,scheduledCalls);noSource();proof("D17")
    }
    @Test fun D18LocalMetadataOnlySelectionNeverRequestsGoogleOrClaimsAnalysis()=runBlocking(Dispatchers.IO) {
        session.localSignOut();val sdk=auth();settings(context).update{it.put("treeUri",source.rootUri)}
        val b=checkNotNull(AutomaticRecordingDiscovery.capture(context));assertEquals("",b.nativeScope)
        assertEquals("WAITING_AUTH",state(run(b.data())));assertEquals(0,sdk.sdk.get());assertEquals(1,localOpens);assertEquals(0,source.opens);proof("D18")
    }
    @Test fun D21ExplicitRevocationRemainsRevokedAfterColdRestart()=runBlocking(Dispatchers.IO) {
        val b=binding();val sdk=auth();session.invalidate(AuthorizationPort.State.REVOKED);restart()
        assertEquals("STALE_NATIVE_SCOPE",state(run(b.data())));assertNull(session.previouslyVerifiedScope());assertNull(AutomaticRecordingDiscovery.capture(scoped))
        assertEquals(0,sdk.sdk.get());noSource();proof("D21")
    }
    @Test fun D22RemovedSyntheticApprovalCannotBeRestoredByQuietRecovery()=runBlocking(Dispatchers.IO) {
        settings.update{it.put("nativeSyntheticApprovals",JSONObject().put("PUBLIC_APPROVAL",JSONObject().put("approved",true)))}
        val b=binding();restart();val sdk=auth().apply{before={settings.update{it.remove("nativeSyntheticApprovals")}}}
        assertEquals("HELD_SOURCE",state(run(b.data())));noSource();assertEquals(0,sdk.owners.get());assertNull(session.accessToken());proof("D22")
    }
    @Test fun D23RunFenceBlocksNextAudioReadAfterAnInFlightReadReturns()=runBlocking(Dispatchers.IO) {
        var allowed=true;var reads=0;source.onRead={reads++;allowed=false}
        val guarded=AutomaticRecordingDiscovery.guarded(source){allowed};val d=source.documents.getValue("one")
        guarded.open(d).use{stream->
            assertEquals(1,stream.read(ByteArray(1)));assertEquals(1,reads)
            try{stream.read(ByteArray(1));fail("READ_AFTER_REVOKE")}catch(e:IllegalStateException){assertEquals("DISCOVERY_RUN_FENCE_CHANGED",e.message)}
        }
        assertEquals(1,reads);proof("D23",JSONObject().put("inFlightReadMayComplete",true).put("nextReadAdmissionsAfterRevocation",0))
    }
    @Test fun D24RestoredPolicyValuesCannotReviveAnOlderWorkOrWatchFence()=runBlocking(Dispatchers.IO) {
        settings.update{it.put("pendingRevocation",false)}
        val before=settings.snapshot();val b=binding();restart();val sdk=auth()
        settings.update{it.put("pendingRevocation",true)};settings.update{it.put("pendingRevocation",false)}
        val after=settings.snapshot()
        assertEquals(AndroidAutomationRuntime.policyHash(before),AndroidAutomationRuntime.policyHash(after))
        assertTrue(after.getLong("dailyReportPolicyEpoch")>before.getLong("dailyReportPolicyEpoch"))
        assertEquals("HELD_SOURCE",state(run(b.data())))
        assertEquals("HELD_SOURCE",AutomaticRecordingDiscovery.cycle(scoped,b,{false}).getString("state"))
        assertEquals(0,sdk.sdk.get());noSource()
        AutomaticRecordingDiscovery.ensure(scoped);val fresh=work.single().second.workSpec.input
        assertNotEquals(b,AutomaticRecordingDiscovery.binding(fresh))
        assertEquals("WAITING_AUTHORIZED_SOURCE",state(run(fresh)));assertEquals(1,sdk.sdk.get())
        proof("D24",JSONObject().put("restoredPolicyHashEqual",true).put("oldBindingAuthAndSourceAdmissions",0).put("explicitEnsureRequiredForFreshFence",true))
    }

    private inner class NativePort(private val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(r:String)=p.submitExisting(r).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun submitBound(r:String,o:(LocalJobStore.Job)->Unit)=p.submitExisting(r,o).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun driveAExport(j:String)=p.driveAExport(j);override fun driveAFrozenRequest(j:String)=p.driveAFrozenRequest(j)
        override fun driveAReopenedReceipt(j:String)=p.driveAReopenedReceipt(j);override fun jobs()=p.jobs();override fun poll(j:String)=p.poll(j)
        override fun retry(j:String):RecordingAnalysisPort.Sent=error("NO_RETRY_OR_NEW_IDS");override fun cancel(){};override fun close(){}
    }
    private inner class PendingProvider:AutomationProvider {
        var dispatches=0;var reads=0
        override fun gate()=AutomationGate("READY","PUBLIC_SYNTHETIC_PENDING_PROVIDER")
        override fun dispatch(k:String,h:String)=AutomationDispatch("ACCEPTED","PUBLIC_RECEIPT_$k","PUBLIC_ACCEPTED").also{dispatches++}
        override fun reconcile(k:String,h:String):AutomationDispatch=error("ALREADY_ACCEPTED")
        override fun receive(k:String,r:String,h:String)=AutomationObservation.Waiting("PUBLIC_RESULT_PENDING").also{reads++}
    }
    private fun automationStore(clock:()->Long=System::currentTimeMillis)=AndroidAutomationStore(scoped,owner,session.epoch(),{true},
        {AndroidAutomationRuntime.policyHash(settings.snapshot())},{AndroidAutomationRuntime.sourceAllowed(scoped,session,it)},{null},clock)
    private fun seedPending(drive:GoogleConnectionRegressionTest.FakeDrive,p:PendingProvider):JSONObject {
        local(scoped).use{it.cycle(1,1)};elapsed=20;local(scoped).use{it.cycle(1,1)};elapsed=40;local(scoped).use{it.cycle(1,1)}
        val recording=GeneralLocalStoreTest.host(scoped,keys).use{s->s.snapshot().getJSONObject(0).getString("file_id").also{s.confirmTime(it,s.snapshot().getJSONObject(0),GeneralLocalStoreTest.NOW-600000)}}
        RecordingWorkflowStore(scoped).use{it.confirmWork(recording,it.snapshot(recording),"합성 담당","합성 회사","합성 기존 업무","")}
        SyntheticApprovedSource.approve(settings,session,source,source.documents.getValue("one"),true)
        return GoogleNativePipeline(context,session,settings,{source},drive,postingCapability={true}){GeneralLocalStoreTest.host(it,keys)}.use{pipeline->
            automationStore().use{s->NativeAutomationRequests(context,session){NativePort(pipeline)}.use{r->AndroidAutomationCoordinator(s,r,p).cycle().single()}}
        }
    }
    private fun immutableState()=db().use{db->JSONObject().apply{
        listOf("google_native_leases","google_native_heads","workflow_analysis_requests","drive_jobs","recordings").forEach{table->
            if(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",table).isNotEmpty())put(table,db.query("SELECT * FROM $table ORDER BY 1").toString())
        }
    }}
    @Test fun D19RecoveredWorkerPreservesOriginalRequestLeaseAndFourFileIdsWithoutResubmission()=runBlocking(Dispatchers.IO) {
        val drive=GoogleConnectionRegressionTest.FakeDrive();val p=PendingProvider();val original=seedPending(drive,p)
        assertEquals("WAITING_RESULT",original.getString("state"));assertEquals(4,drive.counter);assertEquals(1,p.dispatches)
        db().use{it.execute("UPDATE automation_jobs SET next_poll_ms=0")}
        val frozen=immutableState().toString();val b=binding();restart();val sdk=auth();AutomationProviderRegistry.create={_,_,_->p}
        assertEquals("WAITING_RESULT",state(run(b.data())));assertEquals(1,sdk.sdk.get());assertEquals(frozen,immutableState().toString())
        val resumed=automationStore().use{it.jobs().single()}
        listOf("id","owner","epoch","request_id","handoff","receipt","snapshot_json","policy_hash","business_json").forEach{assertEquals(it,original.get(it),resumed.get(it))}
        assertEquals(AutomationPendingResults.deadline(original),AutomationPendingResults.deadline(resumed));assertEquals(4,drive.counter);assertEquals(1,p.dispatches)
        assertEquals(2,p.reads)
        proof("D19",JSONObject().put("originalRequestLeaseAndIdsUnchanged",true).put("nativeIds",drive.counter).put("dispatches",p.dispatches))
    }
    @Test fun D20AuthenticationRecoveryCannotRenewExpiredOriginalSourceLease()=runBlocking(Dispatchers.IO) {
        val drive=GoogleConnectionRegressionTest.FakeDrive();val p=PendingProvider();val original=seedPending(drive,p);val frozen=immutableState().toString()
        val b=binding();restart();auth();assertEquals("READY",GoogleExistingSessionRecovery(session,auth()).recover(owner,b.epoch,b.nativeScope).state)
        val before=p.reads;val expired=checkNotNull(AutomationPendingResults.deadline(original))+1
        val noRequests=object:AutomationRequests {
            override fun prepare(r:String,s:JSONObject):String=error("NO_NEW_REQUEST")
            override fun publish(r:String):String=error("NO_REPUBLISH")
            override fun handoff(r:String):String=error("NO_NEW_FILE_IDS")
            override fun state(r:String):String=error("NO_NATIVE_RETRY")
            override fun close(){}
        }
        val held=automationStore{expired}.use{s->AndroidAutomationCoordinator(s,noRequests,p).cycle().single()}
        assertEquals("FAILED",held.getString("state"));assertEquals("AUTOMATION_RESULT_WINDOW_EXPIRED_OR_INVALID",held.getString("reason"))
        assertEquals(before,p.reads);assertEquals(frozen,immutableState().toString());assertEquals(4,drive.counter);assertEquals(1,p.dispatches)
        proof("D20",JSONObject().put("expiredLeaseRenewals",0).put("expiredResultReads",0).put("coordinatorClockBoundarySynthetic",true))
    }
}
