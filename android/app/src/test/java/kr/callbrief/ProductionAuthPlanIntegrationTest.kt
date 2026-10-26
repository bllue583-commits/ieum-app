package kr.callbrief

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.work.*
import kotlinx.coroutines.*
import kr.callagent.drive.*
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
import java.util.UUID

/** Production setup, encrypted bindings and actual Workers. SDK/HTTP/Drive boundaries are fake;
 * no account, grant, model, source read, remote write or device scheduling is executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProductionAuthPlanIntegrationTest:ProtectedTextHostTestBase() {
    private class Memory:GoogleConnectionSession.Persistence {
        var saved=JSONObject()
        override fun read()=JSONObject(saved.toString())
        override fun write(value:JSONObject){saved=JSONObject(value.toString())}
    }
    private lateinit var context:Context;private lateinit var memory:Memory;private lateinit var session:GoogleConnectionSession
    private var now=1000L
    private val owner="PUBLIC_APPROVED_OWNER";private val account="public.approved@example.invalid"
    private val queue="PUBLIC_ALREADY_APPROVED_QUEUE"
    private val proof="00000000-0000-4000-8000-000000000047"
    private val plan=ScheduledDotProofPlan(true,proof,queue)
    private val credentials="PUBLIC_TEST_ONLY_EXISTING_WORKER_CREDENTIAL"
    private val scheduled=mutableListOf<Pair<String,PeriodicWorkRequest>>()
    private val fixtureKey=javax.crypto.spec.SecretKeySpec(ByteArray(32){0x57},"AES")
    private fun settings(c:Context)=SecureSettings(c,object:SecureSettings.KeyAccess{override fun existing()=fixtureKey;override fun createForFirstEmptyStore()=fixtureKey})
    @Before fun setup()=runBlocking(Dispatchers.IO) {
        context=RuntimeEnvironment.getApplication();memory=Memory();session=GoogleConnectionSession(memory){now}
        val epoch=session.begin();session.activate(epoch,account,account,owner,"PUBLIC_TEST_ONLY_RAM_TOKEN")
        GoogleConnectionSession.installForHost(session)
        GeneralLocalStore.catalog(AccountStorageContext(context,owner))
        ScheduledDotProofRegistry.plan=null;ScheduledDotProofRegistry.preparedInput=null
        ScheduledDotProofRuntime.installWorkForHost{n,r->scheduled+=n to r}
    }
    @After fun cleanup() {
        GoogleConnectionSession.resetForHost();ScheduledDotProofRegistry.plan=null;ScheduledDotProofRegistry.preparedInput=null
        ScheduledDotProofRuntime.installWorkForHost(null);ExistingGoogleSessionRecoveryRuntime.installForHost(null)
        AutomaticResultReconciliation.installForHost()
    }
    private fun restart(){session=GoogleConnectionSession(memory){now};GoogleConnectionSession.installForHost(session)}
    private fun scope()=checkNotNull(session.previouslyVerifiedScope())
    private class Auth(private val account:String,private val owner:String):ExistingGoogleAuthorization {
        var sdkCalls=0;var ownerChecks=0;var needsAction=false
        var before:(()->Unit)?=null
        override fun existingOnly(account:String):ExistingAuthorizationResult {
            assertEquals(this.account,account);sdkCalls++;before?.invoke()
            return ExistingAuthorizationResult(needsAction,if(needsAction)null else "PUBLIC_TEST_ONLY_REFRESHED_RAM_TOKEN")
        }
        override fun verifyOwner(epoch:Long,account:String,token:String):GoogleDriveHttp.Owner {
            assertEquals("PUBLIC_TEST_ONLY_REFRESHED_RAM_TOKEN",token);ownerChecks++;return GoogleDriveHttp.Owner(owner,this.account)
        }
    }
    private fun auth(owner:String=this.owner,account:String=this.account)=Auth(account,owner)
    private fun parameters(s:DriveALocalScope=scope(),attempt:Int=0,key:String=s.key):WorkerParameters {
        val executor=java.util.concurrent.Executor{it.run()}
        return WorkerParameters(UUID.randomUUID(),workDataOf("owner" to s.owner,"epoch" to s.epoch,"native_scope" to key,"proof" to proof),emptyList(),WorkerParameters.RuntimeExtras(),attempt,0,
            executor,Dispatchers.Unconfined,androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor(executor),object:WorkerFactory(){override fun createWorker(c:Context,n:String,p:WorkerParameters):ListenableWorker?=null},
            ProgressUpdater{_,_,_->error("unused")},ForegroundUpdater{_,_,_->error("unused")})
    }
    private fun failure(r:ListenableWorker.Result,state:String,key:String="automation_state") {
        assertTrue("waiting must be failure, not completion",r is ListenableWorker.Result.Failure)
        assertEquals(state,(r as ListenableWorker.Result.Failure).outputData.getString(key))
    }
    private fun blocked(action:()->Unit){try{action();fail("unexpected acceptance")}catch(_:IllegalStateException){}catch(_:IllegalArgumentException){}}
    private fun drive()=GoogleConnectionRegressionTest.FakeDrive().also{
        it.specs[queue]=DriveTransport.FileSpec(queue,null,"PUBLIC_QUEUE",DriveTransport.FOLDER_MIME,owner,DriveTransport.Role.PRIVATE_FOLDER)
        it.bytes[queue]=byteArrayOf();it.versions[queue]=1
    }
    private fun config()=AutomationApiConfiguration(true,"https://public-approved.example.invalid",owner,account,session.epoch(),"Etc/UTC",credentials)
    private fun capabilities(c:AutomationApiConfiguration,observedOwner:String=c.owner)=StrictJson.bytes(mapOf(
        "schema" to "ieum.automation.capabilities.v1","ownerId" to observedOwner,"driveAccount" to c.account,"accountEpoch" to c.epoch,"reportZone" to c.reportZone,
        "resultKind" to AutomationV2Codec.KIND,"resultSchemaVersion" to 2L,"dotInvoked" to false,"driveResultWritten" to false,"canonicalWriteAllowed" to false,
        "automaticUsePolicy" to "IEUM_EXTRACTIVE_SAFE_DERIVED_V1","executionEvidenceSchema" to "ieum.automation.execution-evidence.v1"))

    @Test fun R01ColdRestartRechecksExistingAccountWithoutPersistingTokenOrChangingScope() {
        val s=scope();val id=memory.saved.getString("sessionId");restart();assertNull(session.accessToken());assertNull(DriveALocalScope.current(session))
        val sdk=auth();val r=GoogleExistingSessionRecovery(session,sdk).recover(s.owner,s.epoch,s.key)
        assertEquals("READY",r.state);assertEquals(s.key,DriveALocalScope.current(session)!!.key);assertEquals(id,memory.saved.getString("sessionId"))
        assertEquals(1,sdk.sdkCalls);assertEquals(1,sdk.ownerChecks);assertEquals(s.epoch,session.epoch())
        assertFalse(memory.saved.toString().contains("RAM_TOKEN"));assertFalse(r.toString().contains("TOKEN"))
    }
    @Test fun R02ExpiredLeaseActualResultWorkerRefreshesThenReadsExistingResultsOnly() {
        val s=scope();now+=900001;assertNull(session.accessToken());val sdk=auth();var reads=0
        ExistingGoogleSessionRecoveryRuntime.installForHost{_,_->sdk}
        AutomaticResultReconciliation.installForHost(reconcile={_,_->reads++;JSONObject().put("state","WAITING_RESULT").put("pendingExistingResults",1)})
        assertEquals(ListenableWorker.Result.retry(),AutomationPendingResultWorker(context,parameters(s)).doWork())
        assertEquals(1,reads);assertEquals(1,sdk.sdkCalls);assertEquals(s.key,scope().key)
        assertEquals(ListenableWorker.Result.retry(),AutomationPendingResultWorker(context,parameters(s)).doWork())
        assertEquals(2,reads);assertEquals(1,sdk.sdkCalls);assertFalse(memory.saved.toString().contains("RAM_TOKEN"))
    }
    @Test fun R03SdkResolutionWaitIsFailureAndNeverLaunchesConsentOrReadsResult() {
        val s=scope();restart();val sdk=auth().apply{needsAction=true};var reads=0
        ExistingGoogleSessionRecoveryRuntime.installForHost{_,_->sdk}
        AutomaticResultReconciliation.installForHost(reconcile={_,_->reads++;error("NO_RESULT_BEFORE_AUTH")})
        failure(AutomationPendingResultWorker(context,parameters(s)).doWork(),"WAITING_USER_REAUTH")
        failure(AutomationPendingResultWorker(context,parameters(s)).doWork(),"WAITING_USER_REAUTH")
        assertEquals(1,sdk.sdkCalls);assertEquals(0,sdk.ownerChecks);assertEquals(0,reads);assertNull(session.accessToken())
        assertEquals(GoogleConnectionSession.Phase.CONSENT_REQUIRED,session.view().phase)
        assertEquals("WAITING_USER_REAUTH",memory.saved.getString("backgroundAuthState"))
    }
    @Test fun R04WrongOwnerOrAccountFromRecheckCannotAdoptAnotherIdentity() {
        val s=scope()
        for(pair in listOf("PUBLIC_OTHER_OWNER" to account,owner to "other@example.invalid")) {
            restart()
            val seam=object:ExistingGoogleAuthorization {
                override fun existingOnly(a:String)=ExistingAuthorizationResult(false,"PUBLIC_TEST_ONLY_REFRESHED_RAM_TOKEN").also{assertEquals(account,a)}
                override fun verifyOwner(e:Long,a:String,t:String)=GoogleDriveHttp.Owner(pair.first,pair.second)
            }
            val r=GoogleExistingSessionRecovery(session,seam).recover(s.owner,s.epoch,s.key)
            assertEquals("WAITING_USER_REAUTH",r.state);assertEquals("EXISTING_OWNER_RECHECK_MISMATCH",r.reason)
            assertEquals(s.owner,session.ownerBinding());assertEquals(s.key,scope().key);assertNull(session.accessToken())
        }
    }
    @Test fun R05LogoutDuringSdkAndEnvironmentFailureNeverMutatesNewFence() {
        val s=scope();restart()
        val sdk=auth().apply{before={session.localSignOut();throw GoogleAuthorizationEnvironmentMissing()}}
        val r=GoogleExistingSessionRecovery(session,sdk).recover(s.owner,s.epoch,s.key)
        assertEquals("STALE_NATIVE_SCOPE",r.state);assertEquals(GoogleConnectionSession.Phase.SIGNED_OUT,session.view().phase)
        assertEquals(s.epoch+1,session.epoch());assertEquals("",session.ownerBinding());assertNull(session.previouslyVerifiedScope());assertEquals(0,sdk.ownerChecks)
    }
    @Test fun R06TransientRecheckIsBoundedRetryAndMissingGmsIsExplicitWait() {
        val s=scope();restart();val sdk=auth().apply{before={throw java.io.IOException("PUBLIC_TRANSIENT")}}
        ExistingGoogleSessionRecoveryRuntime.installForHost{_,_->sdk}
        assertEquals(ListenableWorker.Result.retry(),AutomationPendingResultWorker(context,parameters(s)).doWork())
        failure(AutomationPendingResultWorker(context,parameters(s,attempt=6)).doWork(),"RETRY_EXISTING_AUTH")
        sdk.before={throw GoogleAuthorizationEnvironmentMissing()}
        failure(AutomationPendingResultWorker(context,parameters(s)).doWork(),"WAITING_GOOGLE_ENVIRONMENT")
        assertEquals("WAITING_GOOGLE_ENVIRONMENT",session.recoveryState());assertEquals(0,sdk.ownerChecks)
    }
    @Test fun R07StaleLocalBindingIsRejectedBeforeSdkOrResultAccess() {
        val s=scope();restart();val sdk=auth();var reads=0
        ExistingGoogleSessionRecoveryRuntime.installForHost{_,_->sdk};AutomaticResultReconciliation.installForHost(reconcile={_,_->reads++;error("STALE_READ")})
        failure(AutomationPendingResultWorker(context,parameters(s,key="PUBLIC_STALE")).doWork(),"STALE_NATIVE_SCOPE")
        session.localSignOut();failure(AutomationPendingResultWorker(context,parameters(s)).doWork(),"STALE_NATIVE_SCOPE")
        assertEquals(0,sdk.sdkCalls);assertEquals(0,reads)
    }
    @Test fun R08ProductionScheduledSetupRestoresDurablePlanAfterColdRestartAndFactoryReset()=runBlocking(Dispatchers.IO) {
        val d=drive();val s=scope();AutomationApprovedSetup(context,session,d).applyScheduled(plan)
        assertEquals(plan,AutomationConnectionBindings.scheduled(context,session));assertEquals(1,scheduled.size)
        val original=scheduled.single();assertEquals(s.key,original.second.workSpec.input.getString("native_scope"))
        ScheduledDotProofRegistry.plan=null;restart();ScheduledDotProofRuntime.ensure(context)
        assertEquals(2,scheduled.size);assertEquals(original.first,scheduled.last().first)
        assertEquals(proof,scheduled.last().second.workSpec.input.getString("proof"));assertEquals(900000L,scheduled.last().second.workSpec.intervalDuration)
        assertNull(session.accessToken());assertNull(AutomationConnectionBindings.scheduled(context,session))
        assertEquals(plan,AutomationConnectionBindings.scheduled(context,session,true));assertEquals(0,d.counter);assertEquals(0,d.downloads)
    }
    @Test fun R09WithdrawnApprovalAndChangedEpochCannotRescheduleOrSilentlyRebind()=runBlocking(Dispatchers.IO) {
        val d=drive();AutomationApprovedSetup(context,session,d).applyScheduled(plan);scheduled.clear()
        AutomationConnectionBindings.withdraw(context,session);ScheduledDotProofRuntime.ensure(context);assertTrue(scheduled.isEmpty())
        blocked{AutomationApprovedSetup(context,session,d).applyScheduled(plan)};assertTrue(scheduled.isEmpty())
        val e=session.begin();session.activate(e,account,account,owner,"PUBLIC_CHANGED_EPOCH_RAM_TOKEN")
        ScheduledDotProofRuntime.ensure(context);assertTrue(scheduled.isEmpty());assertNull(AutomationConnectionBindings.scheduled(context,session))
    }
    @Test fun R10InvalidPlanAndUnapprovedFolderCannotSaveOrSchedule()=runBlocking(Dispatchers.IO) {
        var metadata=0;val d=drive()
        val unapproved=object:DriveTransport by d {override fun metadata(id:String):DriveTransport.Metadata {
            metadata++;val m=d.metadata(id);return DriveTransport.Metadata(m.id,m.parentId,m.name,m.mimeType,"PUBLIC_OTHER_OWNER",m.version,m.trashed,false,false,m.size)
        }}
        blocked{AutomationApprovedSetup(context,session,unapproved).applyScheduled(plan.copy(proofRequestId="invalid"))};assertEquals(0,metadata)
        blocked{AutomationApprovedSetup(context,session,unapproved).applyScheduled(plan)};assertEquals(1,metadata)
        assertTrue(scheduled.isEmpty());assertNull(AutomationConnectionBindings.scheduled(context,session));assertEquals(0,d.counter)
    }
    @Test fun R11NoPriorApprovedCredentialWaitsWithoutCallingHttpOrBinding()=runBlocking(Dispatchers.IO) {
        var calls=0
        val setup=AutomationApprovedSetup(context,session,drive(),capability={calls++;error("NO_CREDENTIAL_NO_HTTP")})
        assertEquals("WAITING_PREVIOUS_APPROVED_WORKER_SETUP",setup.applyExistingProcessing());assertEquals(0,calls)
        assertNull(AutomationConnectionBindings.processing(context,session))
    }
    @Test fun R12ExistingOwnerScopedApprovalReceiptBindsWithReadOnlyAdditiveV5Capabilities()=runBlocking(Dispatchers.IO) {
        val s=scope();val c=config();val methods=mutableListOf<String>()
        settings(AccountStorageContext(context,owner)).update{it.put("approvedAutomationProcessingSetup",JSONObject()
            .put("schema","ieum.owner-approved-processing-setup.v1").put("approvalState","PREVIOUSLY_APPROVED_SYNTHETIC_ONLY")
            .put("scope",JSONObject(String(StrictJson.bytes(s.binding()),Charsets.UTF_8))).put("origin",c.origin).put("zone",c.reportZone).put("credential",credentials))}
        val setup=AutomationApprovedSetup(context,session,drive(),existingProcessing={ExistingApprovedProcessingSetup.read(context,session,::settings)},capability={cfg->assertEquals(c,cfg);object:AutomationProcessingHttp {
            override fun call(method:String,path:String,body:ByteArray?):AutomationHttpReply {
                methods+=method;assertEquals("GET",method);assertEquals("/v1/automation/capabilities",path);assertNull(body);return AutomationHttpReply(200,capabilities(c))
            }
        }},approvedSettings=::settings)
        assertEquals("PREVIOUS_APPROVED_WORKER_BOUND",setup.applyExistingProcessing());assertEquals(c,AutomationConnectionBindings.processing(context,session,::settings))
        assertEquals(listOf("GET"),methods);assertFalse(c.toString().contains(credentials));assertFalse(memory.saved.toString().contains(credentials))
    }
    @Test fun R13WrongWorkerCapabilitiesOrReceiptScopeCannotBind()=runBlocking(Dispatchers.IO) {
        val c=config();val setup=AutomationApprovedSetup(context,session,drive(),existingProcessing={c},capability={object:AutomationProcessingHttp {
            override fun call(method:String,path:String,body:ByteArray?)=AutomationHttpReply(200,capabilities(c,"PUBLIC_OTHER_OWNER"))
        }})
        blocked{setup.applyExistingProcessing()};assertNull(AutomationConnectionBindings.processing(context,session))
        settings(AccountStorageContext(context,owner)).update{it.put("approvedAutomationProcessingSetup",JSONObject()
            .put("schema","ieum.owner-approved-processing-setup.v1").put("approvalState","PREVIOUSLY_APPROVED_SYNTHETIC_ONLY")
            .put("scope",JSONObject(String(StrictJson.bytes(scope().copy(epoch=session.epoch()+1).binding()),Charsets.UTF_8))).put("origin",c.origin).put("zone",c.reportZone).put("credential",credentials))}
        blocked{ExistingApprovedProcessingSetup.read(context,session,::settings)};assertNull(AutomationConnectionBindings.processing(context,session))
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
    @Test fun R14ProductionConnectionCenterOpensInternalSecureSetupWithoutCredentialIntent() {
        restart()
        val controller=Robolectric.buildActivity(GoogleConnectionRegressionTest.HostConnections::class.java).setup()
        try {
            views(controller.get().window.decorView).single{it.tag=="automation_approved_setup"}.performClick()
            val intent=shadowOf(controller.get()).nextStartedActivity
            assertEquals(AutomationConnectionSetupActivity::class.java.name,intent.component!!.className);assertTrue(intent.extras==null || intent.extras!!.isEmpty)
            val info=context.packageManager.getActivityInfo(intent.component!!,0);assertFalse(info.exported)
            val setup=Robolectric.buildActivity(AutomationConnectionSetupActivity::class.java,intent).setup()
            try {assertTrue(setup.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
                assertEquals(2,views(setup.get().window.decorView).filterIsInstance<EditText>().size)
            }finally{setup.pause().stop().destroy()}
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun R15ScheduledWorkerWaitsForAuthThenRejectsWithdrawnPlanWithoutPublishing()=runBlocking(Dispatchers.IO) {
        val s=scope();AutomationConnectionBindings.bindApprovedScheduled(context,session,plan);AutomationConnectionBindings.withdraw(context,session)
        restart();val sdk=auth().apply{needsAction=true};ExistingGoogleSessionRecoveryRuntime.installForHost{_,_->sdk}
        failure(ScheduledDotProofWorker(context,parameters(s)).doWork(),"WAITING_USER_REAUTH","scheduled_text_state")
        restart();sdk.needsAction=false
        failure(ScheduledDotProofWorker(context,parameters(s)).doWork(),"STALE_OR_WITHDRAWN_PLAN","scheduled_text_state")
        assertEquals(2,sdk.sdkCalls);assertEquals(1,sdk.ownerChecks);assertTrue(scheduled.isEmpty())
    }
    @Test fun R16StopDuringSdkNeverVerifiesOwnerOrActivatesToken() {
        val s=scope();restart();var stopped=false;val sdk=auth().apply{before={stopped=true}}
        val r=GoogleExistingSessionRecovery(session,sdk).recover(s.owner,s.epoch,s.key){stopped}
        assertEquals("LOCAL_WORK_STOPPED",r.state);assertEquals(0,sdk.ownerChecks);assertNull(session.accessToken());assertEquals(s.key,scope().key)
    }
    @Test fun R17MainThreadStartupRestoresApprovedPlanWithoutBlockingLocalDiscovery() {
        runBlocking(Dispatchers.IO){AutomationConnectionBindings.bindApprovedScheduled(context,session,plan)}
        val expected=scope().key;restart();val done=java.util.concurrent.CountDownLatch(1)
        var observed:String?=null
        ScheduledDotProofRuntime.installWorkForHost{_,request->observed=request.workSpec.input.getString("native_scope");done.countDown()}
        // Same main-thread call as AutomaticRecordingDiscovery.ensure / MainActivity.
        ScheduledDotProofRuntime.ensure(context)
        assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(expected,observed);assertNull(session.accessToken())
    }
}
