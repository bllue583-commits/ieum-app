package kr.callbrief

import android.content.Context
import androidx.work.*
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Actual Worker.doWork and registration code, intercepted before OS registration. Synthetic owners only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DailyReportWorkerRecoveryTest : ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var session:GoogleConnectionSession
    private val registry=CaptureRegistry()
    private class CaptureRegistry:DailyReportWorkRegistry {
        val requests=CopyOnWriteArrayList<WorkRequest>();val cancelled=CopyOnWriteArrayList<String>()
        override fun cancel(tag:String) { cancelled+=tag }
        override fun periodic(name:String,request:PeriodicWorkRequest) { requests+=request }
        override fun once(name:String,request:OneTimeWorkRequest) { requests+=request }
    }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        session=GoogleConnectionSession(object:GoogleConnectionSession.Persistence {
            var data=JSONObject()
            override fun read()=JSONObject(data.toString())
            override fun write(value:JSONObject) { data=JSONObject(value.toString()) }
        }) { 1_000L }
        GoogleConnectionSession.installForHost(session);DailyReportSchedule.installRegistryForHost(registry)
    }
    @After fun cleanup() {
        DailyReportSchedule.drainForHost();DailyReportSchedule.installRegistryForHost(null);GoogleConnectionSession.resetForHost()
    }
    private fun activate(owner:String="synthetic_owner_A") {
        val epoch=session.begin();session.activate(epoch,"synthetic@example.invalid","synthetic@example.invalid",owner,"PUBLIC_SYNTHETIC_MEMORY_TOKEN")
    }
    private fun enable():DailyReportRuntime.Scope {
        val scope=DailyReportRuntime.scope(context);scope.context.deleteDatabase(DailyReportLedger.DATABASE);scope.context.deleteDatabase("ieum-daily-reports-v1.db")
        DailyReportLedger(scope.context,scope.profile).use { it.setEnabled(true,Instant.parse("2026-10-10T21:00:00Z"),ZoneId.of("Asia/Seoul")) }
        return scope
    }
    private fun worker(scope:DailyReportRuntime.Scope,generation:Long=1L,contract:Int=DailyReportSchedule.CONTRACT_VERSION):DailyReportWorker {
        val executor=Executor { it.run() }
        val params=WorkerParameters(UUID.randomUUID(),workDataOf("profile" to scope.profile,"account_epoch" to scope.accountEpoch,
            "generation" to generation,"report_contract_version" to contract,
            "trigger" to "SYNTHETIC_WORKER_REPRO"),emptyList(),WorkerParameters.RuntimeExtras(),0,0,
            executor,Dispatchers.Unconfined,WorkManagerTaskExecutor(executor),object:WorkerFactory() {
                override fun createWorker(context:Context,name:String,parameters:WorkerParameters):ListenableWorker?=null
            },ProgressUpdater { _,_,_->error("progress is unused") },ForegroundUpdater { _,_,_->error("foreground is unused") })
        return DailyReportWorker(context,params)
    }
    @Test fun reproV2OldEpochWorkerMustRestoreConfirmedSameOwnerReservation() {
        activate();val old=enable();activate()
        assertEquals(ListenableWorker.Result.success(),runBlocking { worker(old).doWork() })
        DailyReportSchedule.drainForHost()
        val current=DailyReportRuntime.scope(context)
        assertTrue("stale worker must restore requests for confirmed current epoch",registry.requests.any {
            it.workSpec.input.getString("profile")==current.profile && it.workSpec.input.getLong("account_epoch",-1)==current.accountEpoch
        })
    }
    @Test fun pendingAccountSelectionBlocksOldOwnerAndDoesNotRegisterNewWork() {
        activate();val old=enable();session.begin()
        assertThrows(IllegalStateException::class.java) { DailyReportRuntime.scope(context) }
        assertEquals(ListenableWorker.Result.success(),runBlocking { worker(old).doWork() })
        DailyReportSchedule.accountResumed(context,"synthetic_owner_A",old.accountEpoch);DailyReportSchedule.drainForHost()
        assertTrue(registry.requests.isEmpty());assertTrue(registry.cancelled.contains("${DailyReportSchedule.TAG}.profile.${old.profile}"))
        DailyReportLedger(old.context,old.profile).use { assertTrue(it.list().isEmpty()) }
    }
    @Test fun sameEpochRefreshBlocksWhilePendingAndRestoresReservationOnlyAfterConfirmation() {
        activate();val old=enable();assertEquals(old.accountEpoch,session.begin(true))
        assertEquals(ListenableWorker.Result.success(),runBlocking { worker(old).doWork() });assertTrue(registry.requests.isEmpty())
        session.activate(old.accountEpoch,"synthetic@example.invalid","synthetic@example.invalid","synthetic_owner_A","PUBLIC_REFRESH_TOKEN")
        DailyReportSchedule.accountResumed(context,"synthetic_owner_A",old.accountEpoch);DailyReportSchedule.drainForHost()
        assertTrue(registry.requests.any { it is PeriodicWorkRequest && it.workSpec.input.getLong("account_epoch",-1)==old.accountEpoch })
    }
    @Test fun logoutCannotRunOrRestoreOldOwnerWork() {
        activate();val old=enable();session.localSignOut()
        DailyReportRuntime.scope(context).context.deleteDatabase(DailyReportLedger.DATABASE)
        assertEquals(ListenableWorker.Result.success(),runBlocking { worker(old).doWork() })
        DailyReportSchedule.accountResumed(context,"synthetic_owner_A",old.accountEpoch);DailyReportSchedule.drainForHost()
        assertTrue(registry.requests.isEmpty());DailyReportLedger(old.context,old.profile).use { assertTrue(it.list().isEmpty()) }
    }
    @Test fun obsoleteOwnerAWorkerCannotStartEnabledOwnerBWork() {
        activate();val a=enable();activate("synthetic_owner_B");val b=enable()
        assertNotEquals(a.profile,b.profile);assertEquals(ListenableWorker.Result.success(),runBlocking { worker(a).doWork() })
        DailyReportSchedule.drainForHost();assertTrue(registry.requests.isEmpty())
        DailyReportLedger(a.context,a.profile).use { assertTrue(it.list().isEmpty()) }
        DailyReportLedger(b.context,b.profile).use { assertTrue(it.list().isEmpty()) }
    }
    @Test fun actualCurrentWorkerCompletesLocalCheckWithLiveSenderStillDisabled() {
        activate();val current=enable()
        assertEquals(ListenableWorker.Result.success(),runBlocking { worker(current).doWork() })
        assertFalse(BuildConfig.DAILY_REPORT_KAKAO_LIVE_ENABLED)
        assertEquals(kr.callbrief.kakao.core.DeliveryResult.Status.DISABLED,KakaoDailyReportSender(context).gate()!!.status)
        DailyReportLedger(current.context,current.profile).use { ledger ->
            assertTrue(ledger.list().isNotEmpty());assertTrue(ledger.list().all { it.basisHash.isBlank() && it.attempts==0 })
        }
        assertTrue(registry.requests.any { it is PeriodicWorkRequest })
    }
    @Test fun disabledGenerationRejectsQueuedWorkerWithoutSourceReadOrRegistration() {
        activate();val current=enable()
        DailyReportLedger(current.context,current.profile).use { it.setEnabled(false,Instant.EPOCH,ZoneId.of("UTC")) }
        assertEquals(ListenableWorker.Result.success(),runBlocking { worker(current).doWork() });assertTrue(registry.requests.isEmpty())
        DailyReportLedger(current.context,current.profile).use { assertTrue(it.list().isEmpty()) }
    }
    @Test fun legacyContractWorkerCannotExecuteOldWindowsAndRegistersOnlyV2Requests() {
        activate();val current=enable()
        assertEquals(ListenableWorker.Result.success(),runBlocking { worker(current,contract=1).doWork() })
        assertTrue(registry.requests.isNotEmpty());assertTrue(registry.requests.all {
            it.workSpec.input.getInt("report_contract_version",-1)==2 && DailyReportSchedule.TAG in it.tags
        })
        DailyReportLedger(current.context,current.profile).use { assertTrue(it.list().isEmpty()) }
    }
    @Test fun actualMainActivityResumeRestoresChangedEpochWithoutRelaunch() {
        activate();val old=enable()
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            DailyReportSchedule.drainForHost();registry.requests.clear();activity.pause()
            val pending=session.begin();activity.resume();DailyReportSchedule.drainForHost()
            assertTrue("account still awaiting confirmation",registry.requests.isEmpty())
            activity.pause();session.activate(pending,"synthetic@example.invalid","synthetic@example.invalid","synthetic_owner_A","PUBLIC_RESUME_TOKEN")
            activity.resume();DailyReportSchedule.drainForHost()
            assertTrue(registry.requests.any { it.workSpec.input.getLong("account_epoch",-1)==pending })
            assertTrue(registry.requests.none { it.workSpec.input.getLong("account_epoch",-1)==old.accountEpoch })
        } finally { activity.pause().stop().destroy();DailyReportSchedule.drainForHost() }
    }
    @Test fun actualMainActivityResumeAlsoRestoresSameEpochRefresh() {
        activate();val current=enable();val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            DailyReportSchedule.drainForHost();registry.requests.clear();activity.pause();session.begin(true)
            activity.resume();DailyReportSchedule.drainForHost();assertTrue(registry.requests.isEmpty())
            activity.pause();session.activate(current.accountEpoch,"synthetic@example.invalid","synthetic@example.invalid","synthetic_owner_A","PUBLIC_RESUME_REFRESH")
            activity.resume();DailyReportSchedule.drainForHost()
            assertTrue(registry.requests.any { it.workSpec.input.getLong("account_epoch",-1)==current.accountEpoch })
        } finally { activity.pause().stop().destroy();DailyReportSchedule.drainForHost() }
    }
}
