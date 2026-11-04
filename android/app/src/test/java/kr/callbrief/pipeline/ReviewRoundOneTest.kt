package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.core.PrivacyRules
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** Review round 1 (11/4, docs/REVIEW_20261104.md): call times, personal number, quotes, cross-check, broken results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReviewRoundOneTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val now=LocalDate.of(2026,10,28).atTime(10,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private val text=ClaudeCallAnalyzerTest.TRANSCRIPT
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES('rec1',?,'g1','rec1','통화 녹음 홍길동_261027_093000.m4a','audio/mp4',10,0,0,1,1,1)",GeneralLocalStore.OWNER)
            null
        } }
    }
    private fun store()=CallRecordStore(context){ now }
    private fun call(id:String="rec1",at:Long?=now,cp:String?="010-1111-2222",body:String=text)=CallTranscript(id,1,"a".repeat(64),at,60_000,cp,body,emptyList(),"m","b".repeat(64),true)
    private class Inbox:CallInboxStore {
        val files=LinkedHashMap<String,String>()
        override fun put(path:String,json:String){ files.putIfAbsent(path,json) }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha"}
        override fun get(path:String)=files[path] ?: throw ClaudeCallException("INBOX_HTTP_404")
        override fun delete(path:String,sha:String){ files.remove(path) }
    }
    private fun runner(inbox:Inbox,s:JSONObject,at:Long=now,log:List<FirstPass.LogEntry> =emptyList())=CallRecordAnalysisRunner(context,
        ClaudeCallAnalyzer(),{ s },{ at },inbox,save={ e -> e(s) },callLog={ log },smsAllowed={ false })

    @Test fun callsFoundByDiscoveryGetTheirTimeFromTheRecordingName() {
        store().use { it.insert(call(at=null)) }
        port().use { p -> p.execute("CREATE TABLE IF NOT EXISTS local_files(recording_id TEXT PRIMARY KEY,filename_time_candidate_ms INTEGER)");p.execute("INSERT INTO local_files VALUES('rec1',NULL)") }
        val expected=LocalDate.of(2026,10,27).atTime(9,30).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        store().use { db -> assertEquals(expected,db.day(LocalDate.of(2026,10,27)).single().startedMs) }
        assertTrue(ClaudeCallAnalyzer.userMessage(store().use { it.request(it.day(LocalDate.of(2026,10,27)).single()) }).contains("통화 시각: 2026-10-27 09:30"))
    }

    @Test fun aRecordingMatchedToThePersonalNumberIsForgotten() {
        val id=store().use { it.insert(call());CallRecordStore.recordId(call()) }
        store().use { db ->
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01011112222",null,now-20_000,60_000,1,excluded=true)))
            assertNull(db.record(id));assertEquals(0,db.unrecordedCalls())
        }
        // Texts: the same exclusion rule the calls use (here with a stand-in number's hash).
        val rules=kr.callbrief.LocalPrivacy.rules(JSONObject().put("personalPhoneDisabled",true)
            .put("exclusions",JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash("01099998888")))))
        assertTrue(rules.excluded("010-9999-8888","010-9999-8888"));assertFalse(rules.excluded("010-1111-2222","010-1111-2222"))
    }

    @Test fun quotesIgnoreSpacingAndMayComeFromTheTextsDatesMustBeIso() {
        val r=ClaudeCallAnalyzerTest.request().copy(recentTexts="[10/27 문자]\n[09:00 받은 문자] 철근 반입은 목요일 오전으로 확정합니다")
        val a=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray()
            .put(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지, 메일로 보내 드릴게요!",due="2026-11-5"))
            .put(ClaudeCallAnalyzerTest.task("create",null,"반입 확인","철근 반입은 목요일 오전으로 확정합니다",due="2026-10-30"))
            .put(ClaudeCallAnalyzerTest.task("create",null,"지어낸 일","전혀 없는 말"))))
        val f=ClaudeCallAnalyzer().filter(a,r)
        assertEquals(listOf("견적서 보내기","반입 확인"),f.tasks.map { it.title })
        assertNull("not YYYY-MM-DD",f.tasks[0].due);assertEquals("2026-10-30",f.tasks[1].due)
    }

    @Test fun requestsCarryTheCallLogRecentWorkTextsAndNoNumbers() {
        val callId=store().use { db ->
            db.insertMessage("sms:01011112222:5",now-86_400_000L,"01011112222","[09:00 받은 문자] 견적서 금요일까지 부탁드립니다")
            db.applyAnalysis(db.pending().single().recordId,ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis()))
            db.insertMessage("sms:01011112222:6",now-3_600_000L,"01011112222","[09:00 받은 문자] 개인적인 안부")
            db.applyAnalysis(db.pending().single().recordId,ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis().put("business",false)))
            db.insert(call());CallRecordStore.recordId(call()) }
        store().use { db -> db.reconcileCallLog(listOf(FirstPass.LogEntry(7,"01011112222","홍길동",now-20_000,62_000,1))) }
        val req=store().use { it.request(it.record(callId)!!) }
        assertEquals("홍길동",req.callLog?.phoneBookName);assertEquals("받은 전화",req.callLog?.direction)
        assertTrue(req.recentTexts.contains("견적서 금요일까지"));assertFalse("personal ones were deleted",req.recentTexts.contains("안부"))
        val m=ClaudeCallAnalyzer.userMessage(req)
        assertTrue(m.contains("[통화기록] 전화부 이름 홍길동, 받은 전화"));assertTrue(m.contains("<texts>"))
        assertFalse(m.contains("1111"))
    }

    @Test fun aBrokenResultFileIsSetAsideAndTheOthersStillApply() {
        val id=store().use { it.insert(call());CallRecordStore.recordId(call()) }
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        val inbox=Inbox()
        runner(inbox,s).runPending()
        val key=CallInbox.recordKey(id)
        assertEquals(key,JSONObject(inbox.files.values.single()).getString("id"))
        inbox.files.clear()
        inbox.files["results/a-broken.json"]="{not json"
        inbox.files["results/b.json"]=JSONObject().put("id",key).put("analysis",ClaudeCallAnalyzerTest.analysis(JSONArray()
            .put(ClaudeCallAnalyzerTest.task("create",null,"지어낸 일","전혀 없는 말")))).toString()
        assertTrue(runner(inbox,s).runPending())
        assertEquals(setOf("bad/a-broken.json"),inbox.files.keys)
        assertEquals(CallRecordStore.ANALYZED,store().use { it.record(id)!!.state })
        assertEquals(1L,s.getLong(PipelineSettingsKeys.DROPPED_ITEMS))
        // Applying the same result again changes nothing.
        assertTrue(store().use { it.applyAnalysis(id,ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis())) }.isEmpty())
    }

    @Test fun tidyFollowUpsBecomeCheckItemsOnceAndAMissingDueKeepsTheDate() {
        val id=store().use { it.insert(call());CallRecordStore.recordId(call()) }
        store().use { it.applyAnalysis(id,ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray()
            .put(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지",due="2026-10-29"))))) }
        val item=store().use { it.openItems().single() }
        val proposal=JSONObject().put("rev",1).put("tasks",JSONArray()
            .put(JSONObject().put("op","rewrite").put("id",item.id).put("title","김과장에게 견적서 메일 보내기"))
            .put(JSONObject().put("op","create").put("title","김과장 계약서 초안 받았는지 확인").put("due","2026-11-02").put("basis","10/27 김과장 통화: 다음 주 월요일에 받기로 함")))
        store().use { assertEquals(2,it.applyTidy(proposal)) }
        store().use { assertEquals(0,it.applyTidy(JSONObject().put("tasks",JSONArray().put(proposal.getJSONArray("tasks").get(1))))) }
        val open=store().use { it.openItems() }.associateBy { it.title }
        assertEquals("2026-10-29",open.getValue("김과장에게 견적서 메일 보내기").due)
        assertEquals("2026-11-02",open.getValue("김과장 계약서 초안 받았는지 확인").due)
        assertEquals(2,open.size)
    }

    @Test fun aStalledAnalysisIsToldOnceADayInWorkingHours() {
        CallRecordStore(context){ now-7*3_600_000L }.use { it.insert(call()) }
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        val inbox=Inbox()
        runner(inbox,s,at=now-7*3_600_000L).runPending() // sent at 03:00
        assertFalse(s.has(PipelineSettingsKeys.STALL_WARNED_DAY))
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runner(inbox,s,at=now).runPending() // resent at 10:00, still the alert: it counts from when the call was stored
        assertEquals("2026-10-28",s.getString(PipelineSettingsKeys.STALL_WARNED_DAY))
        // Only what is true: nobody else is told yet (the watchdog is not installed), the app keeps trying by itself.
        val posted=org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications
            .map { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()+" "+it.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) }
        assertTrue(posted.toString(),posted.any { it.startsWith("정리가 멈췄어요") && it.contains("매시간 다시 확인해요") })
        assertTrue(posted.none { it.contains("알렸어요") })
    }

    @Test fun theStallNoticeUsesItsOwnStatusChannel() {
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        CallDigestNotifier.post(context,CallDigestWork.STATUS,CallDigestText.Message("정리가 멈췄어요","통화가 6시간 넘게 정리되지 않고 있어요."))
        val m=context.getSystemService(android.app.NotificationManager::class.java)
        assertEquals("이음 상태",m.getNotificationChannel(CallDigestWork.STATUS_CHANNEL).name.toString())
        assertEquals("이음 통화 정리",m.getNotificationChannel(CallDigestWork.CHANNEL).name.toString())
        val posted=org.robolectric.Shadows.shadowOf(m).allNotifications.single()
        assertEquals(CallDigestWork.STATUS_CHANNEL,posted.channelId)
        CallDigestNotifier.post(context,CallDigestSchedule.EVENING,CallDigestText.Message("오늘 통화","1건"))
        assertTrue(org.robolectric.Shadows.shadowOf(m).allNotifications.any { it.channelId==CallDigestWork.CHANNEL })
    }

    private fun settingsView(tag:String):android.view.View {
        val a=org.robolectric.Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        repeat(300) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if(a.window.decorView.findViewWithTag<android.view.View>(tag)!=null) return@repeat;Thread.sleep(10) }
        return a.window.decorView.findViewWithTag<android.view.View>(tag)!!
    }
    private fun toastAfter(click:android.view.View):String? {
        click.performClick()
        repeat(300) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.let { return it };Thread.sleep(10) }
        return null
    }

    @Test fun reportProblemSendsTheHeartbeatNow() {
        val inbox=object:CallInboxStore by Inbox() { val sent=LinkedHashMap<String,String>()
            override fun replace(path:String,json:String){ sent[path]=json } }
        val before=PhoneStatus.reportStore
        try {
            PhoneStatus.reportStore={ inbox }
            val b=settingsView("report_problem")
            assertEquals("문제 알리기",(b as android.widget.TextView).text.toString())
            assertEquals("관리하는 분께 상태를 보냈어요",toastAfter(b))
            assertTrue(JSONObject(inbox.sent.getValue(PhoneStatus.PATH)).has("atMs"))
            org.robolectric.shadows.ShadowToast.reset()
            PhoneStatus.reportStore={ object:CallInboxStore by Inbox() { override fun replace(path:String,json:String){ throw java.io.IOException("offline") } } }
            assertEquals("보내지 못했어요. 인터넷을 확인해 주세요",toastAfter(settingsView("report_problem")))
        } finally { PhoneStatus.reportStore=before }
    }

    @Test fun settingsHelpGroupHasTheSiteMemoToggle() {
        val t=settingsView("site_memo_toggle")
        val memo=t.rootView.findViewWithTag<android.view.View>("site_memo")
        assertEquals(android.view.View.GONE,memo.visibility);t.performClick();assertEquals(android.view.View.VISIBLE,memo.visibility)
    }

    @Test fun settingsShowTheInstalledVersion() {
        val a=org.robolectric.Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        repeat(300) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if(a.window.decorView.findViewWithTag<android.widget.TextView>("app_version")!=null) return@repeat;Thread.sleep(10) }
        val v=a.window.decorView.findViewWithTag<android.widget.TextView>("app_version")
        assertNotNull(v);assertTrue(v.text.toString().startsWith("앱 버전 v"));assertFalse(v.text.contains("·"))
    }
}
