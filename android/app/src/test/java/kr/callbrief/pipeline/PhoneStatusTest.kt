package kr.callbrief.pipeline

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhoneStatusTest {
    private fun input(inbox:String="",update:String="",oldest:Long?=null) = PhoneStatus.Input(1_000L,"v2.1",1034,2,oldest,5,6,"READY",inbox,update,12)

    @Test fun jsonHasWatchdogFieldsAndOmitsEmptyErrors() {
        val o=PhoneStatus.build(input(oldest=500L))
        assertEquals(1_000L,o.getLong("atMs"));assertEquals(500L,o.getLong("oldestQueuedMs"));assertEquals(2,o.getInt("queuedCount"))
        assertEquals("v2.1",o.getString("appVersion"));assertEquals(12,o.getInt("storageUsedPercent"))
        assertFalse(o.has("lastUpdateError"));assertFalse(o.has("lastInboxError"))
    }
    @Test fun nothingWaitingGivesNullAge() { assertTrue(PhoneStatus.build(input()).isNull("oldestQueuedMs")) }
    @Test fun errorsAreCodesOnly() {
        val o=PhoneStatus.build(input(inbox="INBOX_HTTP_401",update="STATUS_FAILURE: 김 부장님 010-1234-5678 통화 내용 ".repeat(5)))
        assertEquals("INBOX_HTTP_401",o.getString("lastInboxError"))
        val u=o.getString("lastUpdateError")
        assertTrue(u.length<=60);assertTrue(u.all { it.code<128 })
        assertFalse(u.contains("010"+" "))
    }
    @Test fun phoneAndOutputHaveNoFreeText() {
        val text=PhoneStatus.build(input(update="한글 이름")).toString()
        assertFalse(Regex("[가-힣]").containsMatchIn(text))
    }

    @Test fun firstPassProgressIsCountsOnly() {
        val o=PhoneStatus.build(input().copy(firstPass=PhoneStatus.FirstPassStatus(false,120,3600,4,2)))
        val f=o.getJSONObject("firstPass")
        assertEquals(120,f.getInt("toTranscribe"));assertEquals(3600L,f.getLong("audioMinutesLeft"));assertEquals(4,f.getInt("waitingForSummary"))
        assertTrue(!PhoneStatus.build(input()).has("firstPass"))
        assertTrue("no bound yet",f.isNull("bound"));assertEquals("none",f.getString("blockedBy"))
    }
    @Test fun firstPassBoundAndWhatHoldsIt() {
        val f=PhoneStatus.build(input(inbox="INBOX_HTTP_422").copy(firstPass=PhoneStatus.FirstPassStatus(false,1,2,3,0,1_790_000_000_000L,FirstPass.BLOCKED_MESSAGES))).also {
            assertEquals("INBOX_HTTP_422",it.getString("lastInboxError")) }.getJSONObject("firstPass")
        assertEquals(1_790_000_000_000L,f.getLong("bound"));assertEquals("waiting_messages",f.getString("blockedBy"))
    }
}

/** Heartbeat fields that tell why a phone is quiet (design B1), and the mailbox token expiry (B2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhoneStatusHealthTest {
    private fun base()=PhoneStatus.Input(1_000L,"v2.4",1040,0,null,5,6,"READY","","",12)

    @Test fun quietPhoneFieldsAreAlwaysThere() {
        val o=PhoneStatus.build(base().copy(health=PhoneStatus.Health(consent=true,folder=false,notifyAllowed=true,batteryFree=false,
            standbyBucket="RARE",lastDiscoveryMs=900,lastUploadMs=800,pendingAnalysis=3,rescheduledMs=700,tokenExpiresMs=2_000_000L,audioChannels=2,audioChannelCorr=0.12345)))
        for(k in listOf("consent","folder","notifyAllowed","batteryFree","standbyBucket","lastDiscoveryMs","lastUploadMs","pendingAnalysis","rescheduledMs","tokenExpiresMs","audioChannels"))
            assertTrue(k,o.has(k))
        assertTrue(o.getBoolean("consent"));assertFalse(o.getBoolean("folder"));assertEquals("RARE",o.getString("standbyBucket"))
        assertEquals(900L,o.getLong("lastDiscoveryMs"));assertEquals(3,o.getInt("pendingAnalysis"));assertEquals(2_000_000L,o.getLong("tokenExpiresMs"))
        assertEquals(2,o.getInt("audioChannels"));assertEquals(0.123,o.getDouble("audioChannelCorr"),1e-9)
        val unknown=PhoneStatus.build(base())
        assertTrue(unknown.isNull("tokenExpiresMs"));assertTrue(unknown.isNull("audioChannels"));assertTrue(unknown.isNull("audioChannelCorr"))
        assertFalse(unknown.has("droppedSegments"));assertFalse(unknown.has("cleaned"))
    }
    @Test fun bucketIsAFixedCode() {
        assertEquals("ACTIVE",PhoneStatus.bucketCode(android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE))
        assertEquals("RESTRICTED",PhoneStatus.bucketCode(45));assertEquals("B99",PhoneStatus.bucketCode(99))
        assertEquals("EXEMPTED",PhoneStatus.bucketCode(5));assertEquals("NEVER",PhoneStatus.bucketCode(50))
        assertEquals("ACTIVE",PhoneStatus.build(base().copy(health=PhoneStatus.Health(standbyBucket="ACTIVE 김부장"))).getString("standbyBucket"))
    }
    @Test fun callLogWindowCountsByTypeAndPersonal() {
        fun e(id:Long,at:Long,type:Int,dur:Long=60_000,key:String?="k$id",personal:Boolean=false)=FirstPass.LogEntry(id,key,"홍길동",at,dur,type,personal)
        val log=listOf(e(1,100,1),e(2,200,2),e(3,300,3,0),e(4,400,5,0),e(5,500,6,0),e(6,600,1,key="01099998888"),e(7,700,2,personal=true),e(8,50,1))
        val w=PhoneStatus.window(log,100,{ it.excluded || it.numberKey=="01099998888" },CallRecordStore.LogTally(7,700,2,1))
        assertEquals(PhoneStatus.CallLogWindow(7,2,2,1,1,1,2,1,2),w)
        val status=PhoneStatus.CallLogStatus(true,100,600,w,PhoneStatus.window(log,600,{ false },CallRecordStore.LogTally(2,700,0,0)),700,700,8,7,555)
        val o=PhoneStatus.build(base().copy(callLog=status)).getJSONObject("callLog")
        assertEquals(7,o.getJSONObject("since").getInt("total"));assertEquals(1,o.getJSONObject("since").getInt("unrecordedCalls"))
        assertEquals(2,o.getJSONObject("today").getInt("total"));assertEquals(8,o.getInt("deviceTotal"));assertEquals(7,o.getInt("storedTotal"))
        assertEquals(555L,o.getLong("mismatchSinceMs"))
        assertFalse(o.toString().contains("9999"));assertFalse(Regex("[가-힣]").containsMatchIn(o.toString()))
        assertFalse(PhoneStatus.build(base()).has("callLog"))
    }
    /** Design 3.6 item 2: the new heartbeat fields, null when the phone does not know yet; counts and codes only. */
    @Test fun watchFieldsAreThereAndHoldNoContent() {
        val keys=listOf("newQueuedCount","newOldestQueuedMs","oldQueuedCount","lastDiscoveryAttemptMs","lastDiscoveryOutcome","inventoryTruncated",
            "heldUnsupported","dbPercent","updateAvailableCode","updateReadySinceMs","callLogMatch","dbOpenMs","homeReadMs","resultChain","callCard")
        val empty=PhoneStatus.build(base())
        for(k in keys) assertTrue(k,empty.has(k))
        for(k in keys.drop(3)) assertTrue(k,empty.isNull(k))
        assertEquals(0,empty.getInt("newQueuedCount"));assertFalse(empty.has("dbRecovery"));assertFalse(empty.has("lastInboxErrorSinceMs"))
        val match=org.json.JSONObject().put("success",12).put("noCandidate",3).put("name","홍길동").put("01012345678",1)
            .put("startGapHistogram",org.json.JSONArray().put(4).put(2).put("010-1111-2222")).put("nested",org.json.JSONObject().put("x",1).put("y","김"))
        val o=PhoneStatus.build(base().copy(lastInboxError="INBOX_HTTP_401",watch=PhoneStatus.Watch(3,900,40,950,"TIME_LIMIT 김부장",true,2,83,1050,800,match,"PROMOTED_NEW",700,45,320,4,1_234),
            callLog=PhoneStatus.CallLogStatus(true,0,0,PhoneStatus.CallLogWindow(),PhoneStatus.CallLogWindow(),null,null,null,0,0)))
        assertEquals(3,o.getInt("newQueuedCount"));assertEquals(900L,o.getLong("newOldestQueuedMs"));assertEquals(40,o.getInt("oldQueuedCount"))
        assertEquals(950L,o.getLong("lastDiscoveryAttemptMs"));assertEquals("TIME_LIMIT",o.getString("lastDiscoveryOutcome"))
        assertTrue(o.getBoolean("inventoryTruncated"));assertEquals(2,o.getInt("heldUnsupported"));assertEquals(83,o.getInt("dbPercent"))
        assertEquals(1050L,o.getLong("updateAvailableCode"));assertEquals(800L,o.getLong("updateReadySinceMs"))
        assertEquals("PROMOTED_NEW",o.getString("dbRecovery"));assertEquals(700L,o.getLong("lastInboxErrorSinceMs"))
        // r8-1: the store open and home read timings ride along (ms only).
        assertEquals(45L,o.getLong("dbOpenMs"));assertEquals(320L,o.getLong("homeReadMs"))
        // r10-3: the result chain's round and next run.
        val chain=o.getJSONObject("resultChain");assertEquals(4,chain.getInt("round"));assertEquals(1_234L,chain.getLong("nextMs"))
        // r11-3: the call card's counts (settings CARD_STATS), numbers only, whatever else was stored there.
        val stats=org.json.JSONObject().put(PipelineSettingsKeys.CARD_STATS,org.json.JSONObject().put("shown",7).put("noPermission",3).put("noNumber",1)
            .put("noCard",2).put("day",20261105).put("today",org.json.JSONObject().put("noPermission",3)).put("010-1111-2222","김"))
        val card=PhoneStatus.build(base().copy(watch=PhoneStatus.Watch(callCard=kr.callbrief.pipeline.call.CardStats.heartbeat(stats)))).getJSONObject("callCard")
        assertEquals(7,card.getInt("shown"));assertEquals(3,card.getInt("noPermission"));assertEquals(1,card.getInt("noNumber"));assertEquals(2,card.getInt("noCard"))
        assertEquals(20261105,card.getInt("day"));assertEquals(3,card.getJSONObject("today").getInt("noPermission"));assertEquals(0,card.getJSONObject("today").getInt("shown"))
        assertFalse(card.toString(),card.toString().contains("010") || Regex("[가-힣]").containsMatchIn(card.toString()))
        assertNull("nothing counted yet",kr.callbrief.pipeline.call.CardStats.heartbeat(org.json.JSONObject()))
        val ended=PhoneStatus.build(base().copy(watch=PhoneStatus.Watch(resultChainRound=0))).getJSONObject("resultChain")
        assertEquals(0,ended.getInt("round"));assertTrue(ended.isNull("nextMs"))
        val m=o.getJSONObject("callLogMatch")
        assertEquals(12,m.getInt("success"));assertEquals(3,m.getInt("noCandidate"));assertFalse(m.has("name"));assertFalse(m.has("01012345678"))
        assertEquals(2,m.getJSONArray("startGapHistogram").length());assertEquals(1,m.getJSONObject("nested").getInt("x"))
        assertEquals(12,o.getJSONObject("callLog").getJSONObject("match").getInt("success"))
        val text=o.toString()
        assertFalse(text,Regex("[가-힣]").containsMatchIn(text));assertFalse(text.contains("0101"));assertFalse(text.contains("1111"))
        // No error now: the since time is not sent either.
        assertFalse(PhoneStatus.build(base().copy(watch=PhoneStatus.Watch(lastInboxErrorSinceMs=700))).has("lastInboxErrorSinceMs"))
    }
    @Test fun sameErrorKeepsItsStart() {
        assertEquals(1_000L,PhoneStatus.sameErrorSince("",0,"INBOX_HTTP_401",1_000))
        assertEquals(1_000L,PhoneStatus.sameErrorSince("INBOX_HTTP_401",1_000,"INBOX_HTTP_401",9_000))
        assertEquals(9_000L,PhoneStatus.sameErrorSince("INBOX_HTTP_401",1_000,"INBOX_HTTP_403",9_000))
        assertEquals(0L,PhoneStatus.sameErrorSince("INBOX_HTTP_401",1_000,"",9_000))
    }
    @Test fun mismatchKeepsItsStart() {
        assertEquals(1_000L,PhoneStatus.mismatchSince(0,5,4,1_000))
        assertEquals(1_000L,PhoneStatus.mismatchSince(1_000,5,4,9_000))
        assertEquals(0L,PhoneStatus.mismatchSince(1_000,4,4,9_000))
        assertEquals(0L,PhoneStatus.mismatchSince(1_000,null,4,9_000)) // no permission: cannot compare
    }
    @Test fun tokenExpiryHeaderIsRead() {
        assertNull(CallInbox.parseTokenExpiry(null));assertNull(CallInbox.parseTokenExpiry(""));assertNull(CallInbox.parseTokenExpiry("nonsense"))
        assertEquals(1764583200000L,CallInbox.parseTokenExpiry("2025-12-01 10:00:00 UTC"))
        assertEquals(1764583200000L,CallInbox.parseTokenExpiry("2025-12-01 02:00:00 -0800"))
        val before=CallInbox.tokenExpiresMs
        try {
            CallInbox.noteTokenExpiry("2025-12-01 10:00:00 UTC");CallInbox.noteTokenExpiry(null) // a reply without the header keeps the last value
            assertEquals(1764583200000L,CallInbox.tokenExpiresMs)
        } finally { CallInbox.tokenExpiresMs=before }
    }
    /** DESIGN_V4 2절 머리 (r12-2 키 커밋): the v3.1 keys are always there, and strings go up as codes only. */
    @Test fun v31KeysAreThereAndHoldNoContent() {
        val empty=PhoneStatus.build(base())
        for(k in listOf("permissions","recordingGap","recordingGapVerdict","latency")) { assertTrue(k,empty.has(k));assertTrue(k,empty.isNull(k)) }
        assertEquals(0,empty.getJSONObject("followup").getInt("noRecording"))
        val r=empty.getJSONObject("results");for(k in listOf("applyFailed","bad","receivedNotApplied")) assertEquals(k,0,r.getInt(k))
        for(k in listOf("transcriptionFailed","droppedItems","duplicateSkipped")) assertEquals(k,0,empty.getInt(k))
        assertFalse(empty.has("storeOpenError"))
        val gap=org.json.JSONObject().put("calls",10).put("withFile",0).put("matched",0).put("newestFileMs",5).put("sinceMs",6).put("verdict","noRecording").put("who","김부장")
        val o=PhoneStatus.build(base().copy(v31=PhoneStatus.V31(PhoneStatus.Permissions(true,false,false,true),gap,"noRecording 김",
            2,"SQLiteException:open 김",1,2,3,4,5,6,org.json.JSONObject().put("p50Min",40).put("p90Min",80).put("n",12))))
        val p=o.getJSONObject("permissions");assertTrue(p.getBoolean("phoneState"));assertFalse(p.getBoolean("overlay"));assertTrue(p.getBoolean("installAllowed"))
        assertEquals(10,o.getJSONObject("recordingGap").getInt("calls"));assertFalse(o.getJSONObject("recordingGap").has("verdict"))
        assertFalse(o.getJSONObject("recordingGap").has("who"))
        assertEquals("noRecording",o.getString("recordingGapVerdict"))
        assertFalse(o.getString("storeOpenError").contains(" "))
        assertEquals(2,o.getJSONObject("followup").getInt("noRecording"));assertEquals(3,o.getJSONObject("results").getInt("receivedNotApplied"))
        assertEquals(40,o.getJSONObject("latency").getInt("p50Min"))
        assertFalse(o.toString().contains("김"))
    }
}

/** The whole heartbeat as the phone sends it, from a phone holding a named call: no name, number or content goes up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[kr.callbrief.ShadowTextKeys::class,kr.callbrief.ShadowTextKeys.ExistingApprovedSettings::class,kr.callbrief.ShadowTextAtomicFile::class])
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class PhoneStatusPublishTest:kr.callbrief.ProtectedTextHostTestBase() {
    @Test fun sentHeartbeatHasNoNameNumberOrContent() {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        kr.co.callsummary.core.android.AndroidSqlPort(context,kr.callbrief.GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",kr.callbrief.GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",kr.callbrief.GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES('rec1',?,'g1','rec1','통화 녹음 홍길동_261027_093000.m4a','audio/mp4',10,0,0,1,1,1)",kr.callbrief.GeneralLocalStore.OWNER)
            null } }
        CallRecordStore(context){ 5_000L }.use { db ->
            db.insert(CallTranscript("rec1",1,"a".repeat(64),4_000L,60_000,"010-1111-2222","홍길동 부장님 내일 현장 콘크리트 타설 3시",emptyList(),"m","b".repeat(64),true))
            db.markSent(db.pending().single().recordId,4_500L)
            db.reconcileCallLog(listOf(FirstPass.LogEntry(77,"01033334444","박영희",FirstPass.FROM_MS+4_200L,30_000,1)))
        }
        kr.callbrief.SecureSettings(context).update { it.put(PipelineSettingsKeys.CONSENT_GRANTED,true).put("treeUri","content://generated/tree")
            .put(kr.callbrief.AutomaticRecordingDiscovery.LAST_DISCOVERY_MS,3_000L).put(PipelineSettingsKeys.RESCHEDULED_MS,2_000L)
            .put(PipelineSettingsKeys.MY_NAME,"김철수") }
        val sent=LinkedHashMap<String,String>()
        val inbox=object:CallInboxStore {
            override fun put(path:String,json:String){ sent[path]=json }
            override fun list(dir:String)=emptyList<Pair<String,String>>()
            override fun get(path:String)=sent[path] ?: throw ClaudeCallException("INBOX_HTTP_404")
            override fun delete(path:String,sha:String){}
        }
        val before=CallInbox.tokenExpiresMs
        try {
            CallInbox.noteTokenExpiry("2026-11-17 00:00:00 UTC")
            PhoneStatus.publish(context,inbox,10_000L)
        } finally { CallInbox.tokenExpiresMs=before }
        val text=sent.getValue(PhoneStatus.PATH);val o=org.json.JSONObject(text)
        assertTrue(o.getBoolean("consent"));assertEquals(3_000L,o.getLong("lastDiscoveryMs"));assertEquals(4_500L,o.getLong("lastUploadMs"))
        assertEquals(1,o.getInt("pendingAnalysis"));assertEquals(2_000L,o.getLong("rescheduledMs"))
        assertEquals(CallInbox.parseTokenExpiry("2026-11-17 00:00:00 UTC"),o.getLong("tokenExpiresMs"))
        val c=o.getJSONObject("callLog")
        assertEquals(1,c.getInt("storedTotal"));assertEquals(FirstPass.FROM_MS+4_200L,c.getLong("latestStoredLogMs"))
        assertFalse(c.getBoolean("allowed"));assertTrue(c.isNull("deviceTotal"));assertEquals(0L,c.getLong("mismatchSinceMs"))
        assertFalse(text,Regex("[가-힣]").containsMatchIn(text))
        for(bad in listOf("010","1111","2222","3333","4444","rec1","content://","treeUri")) assertFalse(bad,text.contains(bad))
        // v2.6 fields are there: the queue split, the store share from the sealed file, unknown keys as null.
        for(k in listOf("newQueuedCount","newOldestQueuedMs","oldQueuedCount","lastDiscoveryAttemptMs","inventoryTruncated","heldUnsupported","updateReadySinceMs","callLogMatch"))
            assertTrue(k,o.has(k))
        assertTrue(o.getInt("dbPercent") in 0..100);assertTrue(o.isNull("lastDiscoveryAttemptMs"))
        // The expiry is kept for the next heartbeat after a restart.
        assertEquals(o.getLong("tokenExpiresMs"),kr.callbrief.SecureSettings(context).snapshot().optLong(PhoneStatus.TOKEN_EXPIRES_MS))
    }

}
