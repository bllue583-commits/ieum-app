package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationManager
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** v3.5 r15-0 (DESIGN_V5 2, r15-0): the v3.4.x bundle. FAILED texts tried once more per build, the numbers by recording, the
 * promises taken out, the lights that tell failed texts and unpaired recordings, the day's call notices in one bundle and the
 * update asked again. Generated rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R15ZeroTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val hour=3_600_000L
    @After fun reset() { CallTranscriptionStore.buildCode={ kr.callbrief.BuildConfig.VERSION_CODE };CallUpdate.clock={ System.currentTimeMillis() } }
    private fun candidate(id:String)=CallTranscriptionStore.Candidate(CallTranscriptionStore.Key(id,1,"a".repeat(64)),"x.m4a","content://x","audio/mp4",1L,false,true)
    private fun port()=AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql"))
    private fun state(s:CallTranscriptionStore,id:String)=s.rows().single { it.first.recordingId==id }.let { it.second to it.third }

    // ---- 1. FAILED texts once more for each new build ----
    @Test fun aFailedTextGoesBackToTheQueueOnceForEachNewBuild() {
        var clock=1_800_000_000_000L
        CallTranscriptionStore.buildCode={ 1045 }
        CallTranscriptionStore(app,{ clock }).use { s ->
            val c=candidate("old");val d=candidate("now")
            s.offer(c,true,"");s.offer(d,true,"")
            repeat(3) { assertTrue(s.claim(c.key));s.finish(c.key,if(it<2) "QUEUED" else "FAILED","STALLED") }
            assertTrue(s.claim(d.key));s.finish(d.key,"FAILED","DECODE")
            assertEquals(2,s.failedCount())
            // The same build does not try its own failures again.
            assertEquals(0,s.requeueFailedOnce(1045))
        }
        // A row failed by a build from before v3.5 carries no build at all: it comes back too.
        port().use { it.execute("UPDATE call_transcription_jobs SET failed_build=NULL WHERE recording_id='old'") }
        clock+=hour
        CallTranscriptionStore.buildCode={ 1046 }
        CallTranscriptionStore(app,{ clock }).use { s ->
            assertEquals(CallTranscriptionStore.REQUEUED to 0,state(s,"old").let { it.second });assertEquals("QUEUED",state(s,"old").first)
            assertEquals("QUEUED",state(s,"now").first);assertEquals(0,s.attempts(candidate("now").key))
            assertEquals("the queue age starts now",clock,s.oldestQueuedMs())
            assertEquals(0,s.failedCount())
            assertEquals(2,s.heartbeat(clock).getInt("requeuedFailed"))
            // It fails again under the new build: it stays failed, however often the store opens.
            repeat(3) { assertTrue(s.claim(candidate("old").key));s.finish(candidate("old").key,if(it<2) "QUEUED" else "FAILED","STALLED") }
        }
        CallTranscriptionStore(app,{ clock }).use { s ->
            assertEquals("FAILED",state(s,"old").first);assertEquals(0,s.requeueFailedOnce(1046))
            assertEquals("the heartbeat keeps counting what this build revived",2,s.heartbeat(clock).getInt("requeuedFailed"))
        }
        // The build after that tries it once more.
        CallTranscriptionStore.buildCode={ 1047 }
        CallTranscriptionStore(app,{ clock }).use { s ->
            assertEquals("QUEUED",state(s,"old").first);assertEquals(1,s.heartbeat(clock).getInt("requeuedFailed"))
        }
    }
    @Test fun aRunningRowThatDiesAtItsLastAttemptIsMarkedWithItsBuild() {
        var clock=1_800_000_000_000L
        CallTranscriptionStore.buildCode={ 1046 }
        CallTranscriptionStore(app,{ clock }).use { s ->
            val c=candidate("dead");s.offer(c,true,"")
            repeat(3) { assertTrue(s.claim(c.key));if(it<2) s.finish(c.key,"QUEUED","X") }
            clock+=CallTranscriptionStore.STALE_RUNNING_MS+1
            s.recoverRunning(CallTranscriptionRunner.MAX_ATTEMPTS)
            assertEquals("FAILED",state(s,"dead").first)
        }
        CallTranscriptionStore(app,{ clock }).use { s -> assertEquals("FAILED",state(s,"dead").first) }
    }

    // ---- 2. the story is never cut in the middle of a sentence ----
    @Test fun theStoryKeepsWholeSentences() {
        val two="아침에 탄탄이엔씨 김민수 대표와 철근 단가를 다시 보기로 했어요. 오후에는 한빛건설 이영희 과장과 타설 일정을 금요일로 옮겼어요."
        val long="$two 저녁에는 대한전기 박정우 소장이 배선 검측을 마쳤다고 알려 왔고, 다음 주 월요일에 서류를 보내 주기로 했어요."
        assertTrue(long.length>TodayStory.MAX_CHARS)
        assertEquals(two,TodayStory.clip(long))
        assertEquals(two,TodayStory.clip(two))
        val one="쉼표 없이 아주 길게 이어지는 한 문장 ".repeat(10).trim()+"이에요."
        val cut=TodayStory.clip(one);assertTrue(cut.length<=TodayStory.MAX_CHARS);assertTrue(one.startsWith(cut));assertFalse(cut.endsWith(" "))
        val story=JSONObject().put("day",LocalDate.now(CallRecordStore.KST).toString()).put("text",long).put("calls",3).put("atMs",1).toString()
        assertEquals(two,TodayStory.shown(story,LocalDate.now(CallRecordStore.KST),emptyList())!!.text)
    }

    // ---- 3. the numbers count recordings ----
    @Test fun theNumbersCountRecordingsAndTheEveningTitleSaysHowManyAreSummedUp() {
        assertEquals("녹음 5개 중 정리 3개",TodayStory.countLine(5,3))
        assertEquals("never fewer recordings than summaries","녹음 4개 중 정리 4개",TodayStory.countLine(2,4))
        fun rec(id:String,summary:String?)=CallRecord(id,id,1L,60_000,"상대",null,"",if(summary==null) "TRANSCRIBED" else CallRecordStore.ANALYZED,summary,null,emptyList(),null,1L,null)
        val m=CallDigestText.evening(listOf(rec("a","견적서를 보내기로 했어요."),rec("b",null)),1,recordings=5,logged=20)
        assertEquals("녹음 5개 중 정리 1개, 새 체크할 일 1개",m.title)
        assertTrue(m.text,m.text.endsWith("아직 정리 중인 통화가 4건 있어요"))
        assertFalse("the call log's own count is not shown",m.title.contains("20"))
        assertEquals("오늘은 녹음된 통화가 없어요",CallDigestText.evening(emptyList(),0,recordings=0,logged=4).title)
        assertEquals("녹음 0개 중 정리 0개, 새 체크할 일 0개",CallDigestText.evening(emptyList(),0,recordings=0,logged=0).title)
    }
    @Test fun recordingsWithoutTheirRecordCountForTheirDay() {
        CallTranscriptionStore(app).close()
        val day=LocalDate.of(2026,11,6);val at=day.atTime(10,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("q" to "QUEUED","f" to "FAILED","d" to "DONE","s" to "SKIPPED").forEachIndexed { i,(id,st) ->
                p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,?,'audio/mp4',10,0,0,1,1,1,?)",
                    id,GeneralLocalStore.OWNER,id,"$id.m4a",at+i*60_000L)
                p.execute("INSERT INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,reason,updated_ms,failed_build) VALUES(?,1,?,?,'',0,?)",id,"a".repeat(64),st,kr.callbrief.BuildConfig.VERSION_CODE) }
            null } }
        val q:(String,Array<Any?>)->List<Map<String,Any?>> ={ sql,args -> port().use { it.query(sql,*args) } }
        assertEquals("queued and failed, not done or skipped",2,CallTranscriptionStore.withoutRecord(day,q))
        assertEquals(0,CallTranscriptionStore.withoutRecord(day.plusDays(1),q))
    }

    // ---- 4. no promises ----
    @Test fun theWordsPromiseNoTimeAndNoReport() {
        assertEquals("정리하고 있어요",CallUi.state(CallRecordStore.TRANSCRIBED))
        val e=CallFollowupNotice.Entry(1,"박지성",null,"h",0,60_000)
        assertEquals(CallFollowupNotice.WORKING_WORDS,CallFollowupNotice.words(e,null).text)
        assertEquals(CallFollowupNotice.LATE_WORDS,CallFollowupNotice.words(e.copy(state=CallFollowupNotice.LATE,record="r"),null).text)
        listOf(CallUi.state(CallRecordStore.TRANSCRIBED),CallFollowupNotice.WORKING_WORDS,CallFollowupNotice.LATE_WORDS).forEach { w ->
            assertFalse(w,w.contains("시간 안") || w.contains("보고에 넣어") || w.contains("요약이 와요")) }
    }

    // ---- 5. the lights tell failed texts and unpaired recordings, and point the right way ----
    @Test fun theLightsTellFailedTextsAndRecordingsThatDoNotPairWithCalls() {
        val now=1_800_000_000_000L
        val ok=StatusLights.Input(now=now,folderConnected=true,modelState="READY",consent=true,lastTranscribedMs=now-hour,folderReadable=true,lastDiscoveryMs=now-hour)
        assertTrue(StatusLights.lights(ok).all { it.ok })
        val failed=StatusLights.lights(ok.copy(textFailed=12)).single { !it.ok }
        assertEquals("status_transcribe",failed.tag);assertEquals(StatusLights.textFailedWords(12),failed.words);assertTrue(failed.words.contains("12건"))
        val nm=StatusLights.lights(ok.copy(noMatchLong=true)).single { !it.ok }
        assertEquals("status_import",nm.tag);assertEquals(StatusLights.NO_MATCH_WORDS,nm.words)
        // noMatch held over two days turns it on; a younger or stale verdict does not.
        fun s(since:Long,at:Long)=JSONObject().put(PipelineSettingsKeys.RECORDING_GAP,RecordingGap.Verdict(PipelineSettingsKeys.GAP_NO_MATCH,10,9,1,null,since,at).json().toString())
        assertTrue(StatusLights.noMatchLong(s(now-49*hour,now-hour),now))
        assertFalse(StatusLights.noMatchLong(s(now-47*hour,now-hour),now))
        assertFalse(StatusLights.noMatchLong(s(now-100*hour,now-RecordingGap.STALE_MS-hour),now))
        // With an amber light the lights lead the page, so the groups they name are below them.
        val lost=StatusLights.lights(ok.copy(folderReadable=false)).single { !it.ok }
        assertTrue(lost.words,lost.words.contains("아래 연결 묶음에서") && !lost.words.contains("위 "))
        assertEquals("아래 정리 묶음에서 켜 주세요.",StatusLights.below("위 정리 묶음에서 켜 주세요."))
        StatusLights.lights(ok.copy(textFailed=1,folderConnected=false,consent=false)).forEach { assertFalse(it.words,Regex("[·…]").containsMatchIn(it.words) || it.words.contains("위 ")) }
    }

    // ---- 6. the day's call notices in one bundle ----
    @Test fun theNoticesFoldIntoOneBundleWithASummary() {
        assertEquals("통화 알림 5개" to "정리됨 2건, 정리 중 2건, 확인할 통화 1건",
            CallFollowupNotice.summaryWords(listOf("done","done","working","hold","late")))
        assertEquals("통화 알림 2개" to "정리 중 2건",CallFollowupNotice.summaryWords(listOf("working","working")))
        // Two notices on the phone: they share the group and a quiet summary comes with them; one left takes it away.
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager=app.getSystemService(NotificationManager::class.java)
        val t0=System.currentTimeMillis()-hour
        SecureSettings(app).update { it.put(CallFollowupNotice.STATE_KEY,org.json.JSONArray()
            .put(CallFollowupNotice.Entry(11,"김민수 대표",null,"h1",t0,t0+60_000).json())
            .put(CallFollowupNotice.Entry(12,"이영희 과장",null,"h2",t0+hour/2,t0+hour/2+60_000).json()).toString()) }
        CallFollowupNotice.sweep(app,t0+CallFollowupNotice.LATE_MS+hour) // both turn late and are posted
        val shown=manager.activeNotifications.filter { it.tag==CallFollowupNotice.TAG }
        assertEquals(3,shown.size)
        assertTrue(shown.all { it.notification.group==CallFollowupNotice.GROUP })
        val summary=shown.single { it.id==CallFollowupNotice.SUMMARY_ID }.notification
        assertTrue((summary.flags and Notification.FLAG_GROUP_SUMMARY)!=0)
        assertEquals("통화 알림 2개",summary.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        CallFollowupNotice.afterEvening(app,t0+hour/4) // the first call is in the evening report now
        val left=manager.activeNotifications.filter { it.tag==CallFollowupNotice.TAG }
        assertEquals(listOf(12),left.map { it.id })
    }

    // ---- 8. the update asked again, and the reports' line ----
    @Test fun aSessionWaitingForTheTapForADayIsAskedAgain() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        var now=1_800_000_000_000L;CallUpdate.clock={ now }
        val next=kr.callbrief.BuildConfig.VERSION_CODE+1
        val apk=ByteArray(20_000) { (it%251).toByte() }
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        val store=object:CallInboxStore {
            override fun put(path:String,json:String) {}
            override fun list(dir:String)=emptyList<Pair<String,String>>()
            override fun delete(path:String,sha:String) {}
            override fun get(path:String)=JSONObject().put("versionCode",next).put("versionName","v3.5").put("apk","releases/assets/1").put("sha256",sha).put("notes","").put("size",apk.size).toString()
            override fun download(path:String,to:java.io.File,progress:(Long,Long)->Unit) { to.writeBytes(apk);progress(apk.size.toLong(),apk.size.toLong()) }
        }
        val installer=app.packageManager.packageInstaller
        assertFalse(CallUpdate.installWaiting(app))
        assertTrue(CallUpdate.check(app,store))
        assertTrue(CallUpdate.installWaiting(app))
        val first=installer.allSessions.single().sessionId
        // Android wants a tap; the notification is swiped away.
        CallUpdate.pendingUserAction(app)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        now+=12*hour
        assertTrue(CallUpdate.check(app,store))
        assertEquals("within a day: still the same session",listOf(first),installer.allSessions.map { it.sessionId })
        now+=13*hour
        assertTrue(CallUpdate.confirmOverdue(app,now))
        assertTrue(CallUpdate.check(app,store))
        val again=installer.allSessions.map { it.sessionId }
        assertEquals(1,again.size);assertNotEquals("over a day: given up and committed again",first,again.single())
        assertFalse(CallUpdate.confirmOverdue(app,now))
        // The reports' line, only while it waits.
        val m=CallDigestText.Message("체크할 일 1개","지금 할 일: 견적서",listOf("지금 할 일: 견적서"))
        assertEquals(listOf("지금 할 일: 견적서",CallDigestText.UPDATE_LINE),CallDigestText.withUpdate(m,true).lines)
        assertEquals(m,CallDigestText.withUpdate(m,false))
        val full=m.copy(lines=(1..CallDigestText.MAX_LINES).map { "줄 $it" })
        assertEquals(CallDigestText.MAX_LINES,CallDigestText.withUpdate(full,true).lines.size)
        val base=SelfCheckNotices.Input(now,true,true,"READY",0,null,now,false,null,now,null)
        assertEquals(listOf(SelfCheckNotices.UPDATE_WAITING),SelfCheckNotices.check(base.copy(updateWaiting=true)).map { it.code })
        assertEquals(CallDigestText.UPDATE_LINE,SelfCheckNotices.check(base.copy(updateWaiting=true)).single().title)
        assertEquals("the day-old notice says it instead",listOf(SelfCheckNotices.UPDATE_STUCK),SelfCheckNotices.check(base.copy(updateWaiting=true,updateStuck=true)).map { it.code })
        assertTrue(SelfCheckNotices.check(base).isEmpty())
    }
}
