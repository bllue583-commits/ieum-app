package kr.callbrief.pipeline.call

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.core.PrivacyRules
import kr.callbrief.pipeline.call.CallSession.Direction
import kr.callbrief.pipeline.call.CallSession.State
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.TimeUnit

/** v2.9 r10-1 (design 6.1): PHONE_STATE broadcasts through [CallStateReceiver] into [CallSession]: the state machine, the
 * after-call discovery work, the listeners, personal and excluded numbers, and the state read back after the process died.
 * Synthetic numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallSessionTest:ProtectedTextHostTestBase() {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private var now=1_800_000_000_000L
    private val ends=mutableListOf<CallSession.CallEnd>()
    private var afterCalls=0
    private val number="010-0000-1111";private val key="01000001111"

    @Before fun fresh() {
        CallSession.resetForHost()
        CallSession.clock={ now }
        CallSession.listeners+= { e:CallSession.CallEnd -> synchronized(ends) { ends+=e } }
        CallSession.afterCall={ afterCalls++;true }
    }
    @After fun done() { CallSession.resetForHost() }

    private fun broadcast(state:String,raw:String?=null,advance:Long=1_000L) {
        now+=advance
        val intent=Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE,state)
        if(raw!=null) intent.putExtra("incoming_number",raw)
        CallStateReceiver().onReceive(app,intent)
        CallSession.awaitIdle()
    }
    private val ringing=TelephonyManager.EXTRA_STATE_RINGING
    private val offhook=TelephonyManager.EXTRA_STATE_OFFHOOK
    private val idle=TelephonyManager.EXTRA_STATE_IDLE

    @Test fun incomingCallRingsGoesOffHookAndEndsWithOneAfterCallWork() {
        broadcast(ringing,number)
        CallSession.current().let { assertEquals(State.RINGING,it.state);assertEquals(key,it.phoneKey);assertEquals(Direction.IN,it.direction);assertEquals(now,it.sinceMs) }
        val rangAt=now
        broadcast(offhook,number,advance=4_000)
        CallSession.current().let { assertEquals(State.OFFHOOK,it.state);assertEquals(key,it.phoneKey);assertEquals(Direction.IN,it.direction) }
        assertEquals(0,afterCalls);assertTrue(ends.isEmpty())
        broadcast(idle,advance=60_000)
        assertEquals(State.IDLE,CallSession.current().state);assertNull(CallSession.current().phoneKey)
        assertEquals(1,afterCalls)
        assertEquals(listOf(CallSession.CallEnd(key,Direction.IN,rangAt,now,true)),ends)
    }
    @Test fun repeatedBroadcastsChangeNothingButAMissingNumber() {
        // Android 9 and later: every state twice, the number only in the second one.
        broadcast(ringing,null);assertNull(CallSession.current().phoneKey)
        val since=CallSession.current().sinceMs
        broadcast(ringing,number)
        CallSession.current().let { assertEquals(State.RINGING,it.state);assertEquals(key,it.phoneKey);assertEquals(since,it.sinceMs) }
        broadcast(ringing,"010-0000-2222") // a filled number is not replaced
        assertEquals(key,CallSession.current().phoneKey)
        broadcast(offhook,null);broadcast(offhook,number)
        broadcast(idle);broadcast(idle);broadcast(idle,number)
        assertEquals(1,afterCalls);assertEquals(1,ends.size);assertEquals(key,ends.single().phoneKey)
    }
    @Test fun offHookWithoutRingingIsAnOutgoingCallWithoutNumber() {
        broadcast(offhook)
        CallSession.current().let { assertEquals(State.OFFHOOK,it.state);assertEquals(Direction.OUT,it.direction);assertNull(it.phoneKey) }
        val start=now
        broadcast(idle,advance=30_000)
        assertEquals(listOf(CallSession.CallEnd(null,Direction.OUT,start,now,true)),ends);assertEquals(1,afterCalls)
    }
    @Test fun missedCallIsToldButLooksForNoRecording() {
        broadcast(ringing,number);broadcast(idle,advance=20_000)
        assertEquals(0,afterCalls)
        assertFalse(ends.single().answered);assertEquals(key,ends.single().phoneKey)
    }
    @Test fun aSecondCallRingingDuringACallKeepsTheFirstOne() {
        broadcast(ringing,number);broadcast(offhook,number)
        broadcast(ringing,"010-0000-2222")
        CallSession.current().let { assertEquals(State.OFFHOOK,it.state);assertEquals(key,it.phoneKey) }
        broadcast(idle);assertEquals(1,ends.size)
    }
    @Test fun otherActionsAndUnknownStatesAreIgnored() {
        CallStateReceiver().onReceive(app,Intent("kr.callbrief.FAKE").putExtra(TelephonyManager.EXTRA_STATE,offhook))
        CallSession.awaitIdle()
        broadcast("SOMETHING")
        assertEquals(State.IDLE,CallSession.current().state);assertEquals(0,afterCalls)
    }
    @Test fun personalNumberDoesNothingAtAll() {
        kr.callbrief.IeumApplication.store(app).rememberPersonal(key)
        broadcast(ringing,number)
        CallSession.current().let { assertEquals(State.RINGING,it.state);assertNull(it.phoneKey);assertTrue(it.ignored) }
        broadcast(offhook,number);broadcast(idle)
        assertEquals(0,afterCalls);assertTrue(ends.isEmpty())
        assertFalse(SecureSettings(app).snapshot().toString().contains(key))
    }
    @Test fun excludedNumberArrivingLateDoesNothingAtAll() {
        SecureSettings(app).update { it.put("exclusions",JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash(key)))) }
        broadcast(ringing,null);broadcast(ringing,number)
        assertTrue(CallSession.current().ignored);assertNull(CallSession.current().phoneKey)
        broadcast(offhook);broadcast(idle)
        assertEquals(0,afterCalls);assertTrue(ends.isEmpty())
    }
    @Test fun stateComesBackFromSettingsAfterTheProcessDied() {
        broadcast(ringing,number);val rangAt=now;broadcast(offhook)
        CallSession.forgetMemory() // the process is killed during the call
        CallSession.current() // before the first broadcast nothing has the context: IDLE
        broadcast(idle,advance=120_000)
        assertEquals(listOf(CallSession.CallEnd(key,Direction.IN,rangAt,now,true)),ends);assertEquals(1,afterCalls)
        // The key is gone from settings once the call ended.
        assertTrue(SecureSettings(app).snapshot().getJSONObject(CallSession.SETTINGS_KEY).isNull("k"))
    }
    @Test fun aStateLeftOverForHoursIsNotTakenBack() {
        broadcast(ringing,number);broadcast(offhook)
        CallSession.forgetMemory()
        now+=CallSession.STALE_MS+1
        broadcast(idle)
        assertTrue(ends.isEmpty());assertEquals(0,afterCalls)
    }

    // ---- the work itself (real WorkManager whose workers never run) ----
    private fun workManager():WorkManager {
        runCatching { androidx.work.impl.WorkManagerImpl.setDelegate(null) }
        WorkManager.initialize(app,Configuration.Builder().setExecutor { }.setMinimumLoggingLevel(android.util.Log.ERROR).build())
        return WorkManager.getInstance(app)
    }
    @Test fun endOfACallQueuesTheExpeditedAfterCallDiscovery() {
        val wm=workManager()
        CallSession.afterCall={ c -> afterCalls++;AutomaticRecordingDiscovery.scheduleAfterCall(c) }
        // No folder chosen: nothing to look at, nothing queued.
        broadcast(offhook);broadcast(idle)
        assertEquals(1,afterCalls);assertTrue(wm.getWorkInfosForUniqueWork(AutomaticRecordingDiscovery.AFTERCALL_NAME).get(5,TimeUnit.SECONDS).isEmpty())
        SecureSettings(app).update { it.put("treeUri","content://generated-fixture/tree/test") }
        broadcast(ringing,number);broadcast(offhook);broadcast(idle)
        val works=wm.getWorkInfosForUniqueWork(AutomaticRecordingDiscovery.AFTERCALL_NAME).get(5,TimeUnit.SECONDS)
        assertEquals(1,works.size)
        assertTrue(works.single().tags.contains(AutomaticRecordingDiscovery.AFTERCALL_NAME))
        assertFalse(works.single().state.isFinished)
        // A second call right after is appended, not lost.
        broadcast(offhook);broadcast(idle)
        assertEquals(2,wm.getWorkInfosForUniqueWork(AutomaticRecordingDiscovery.AFTERCALL_NAME).get(5,TimeUnit.SECONDS).count { it.state!=WorkInfo.State.CANCELLED })
    }
    @Test fun afterCallRequestIsExpeditedAndCarriesOnlyTheFence() {
        val spec=AutomaticRecordingDiscovery.afterCallRequest(AutomaticRecordingDiscovery.Binding("b".repeat(64))).workSpec
        assertTrue(spec.expedited);assertEquals(0L,spec.initialDelay)
        assertEquals(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,spec.outOfQuotaPolicy)
        assertEquals(setOf("selection_fence"),spec.input.keyValueMap.keys)
        assertEquals(15_000L,AutomaticRecordingDiscovery.AFTERCALL_WAIT_MS)
        assertEquals("ieum.discovery.aftercall",AutomaticRecordingDiscovery.AFTERCALL_NAME)
    }
    @Test fun manifestDeclaresThePermissionAndTheExportedReceiver() {
        val pm=app.packageManager
        val info=pm.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_RECEIVERS)
        assertTrue(info.requestedPermissions!!.contains(android.Manifest.permission.READ_PHONE_STATE))
        val receiver=info.receivers!!.single { it.name==CallStateReceiver::class.java.name }
        assertTrue(receiver.exported)
        val found=pm.queryBroadcastReceivers(Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).setPackage(app.packageName),0)
        assertTrue(found.any { it.activityInfo.name==CallStateReceiver::class.java.name })
    }
}
