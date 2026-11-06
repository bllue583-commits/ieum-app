package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationManager
import android.net.Uri
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.report.KakaoReport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v3.5 r15-4 (independent review, 11/6): the failed texts read on the IO side only (H1), the quiet morning notice on a
 * KakaoTalk day (H2), the FAILED revival cap (M4) and the KakaoTalk button's day kept in range (L2). Generated rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R15FourTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val h=3_600_000L
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    private fun candidate(id:String)=CallTranscriptionStore.Candidate(CallTranscriptionStore.Key(id,1,"a".repeat(64)),"x.m4a","content://x","audio/mp4",1L,false,true)
    private fun state(s:CallTranscriptionStore,id:String)=s.rows().single { it.first.recordingId==id }.second

    @Before fun setup() { shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);CallDigestKakao.sending={ false } }
    @After fun reset() {
        CallTranscriptionStore.buildCode={ kr.callbrief.BuildConfig.VERSION_CODE }
        KakaoReport.appKey={ kr.callbrief.BuildConfig.KAKAO_APP_KEY };CallDigestKakao.sending=null
        manager.cancelAll()
    }
    private fun failOnce(s:CallTranscriptionStore,id:String,reason:String="STALLED") {
        repeat(CallTranscriptionRunner.MAX_ATTEMPTS) { assertTrue(s.claim(candidate(id).key));s.finish(candidate(id).key,if(it<CallTranscriptionRunner.MAX_ATTEMPTS-1) "QUEUED" else "FAILED",reason) }
    }

    // ---- H1 ----
    @Test fun theFailedTextsAreReadWithTheStoreAndNotOnTheMainThread() {
        CallTranscriptionStore.buildCode={ 2000 }
        CallTranscriptionStore(app).use { s -> s.offer(candidate("a"),true,"");failOnce(s,"a") }
        val store=StatusLights.read(app,JSONObject(),System.currentTimeMillis())
        assertEquals(1,store.textFailed)
        // withPhone opens no store: the count is what the IO read handed in.
        val now=System.currentTimeMillis()
        val ok=StatusLights.Input(now=now,folderConnected=true,modelState="READY",consent=true,lastTranscribedMs=now-h,lastAnalyzedMs=now-h,lastDiscoveryMs=now)
        assertEquals(0,StatusLights.withPhone(ok,app,JSONObject(),null).textFailed)
        assertEquals(1,StatusLights.withPhone(ok.copy(textFailed=store.textFailed ?: 0),app,JSONObject(),null).textFailed)
    }

    // ---- H2 ----
    private fun morning()=shadowOf(manager).allNotifications.filter { it.channelId==CallDigestWork.CHANNEL }
    private val message=CallDigestText.Message("체크할 일 1개","지금 할 일: 견적서 보내기",listOf("지금 할 일: 견적서 보내기"))
    @Test fun aKakaoDayWithTheUpdateOrLateLineStillRings() {
        KakaoReport.appKey={ "abc123" }
        SecureSettings(app).update { it.put(PipelineSettingsKeys.KAKAO_LINKED,true).put(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,System.currentTimeMillis()) }
        assertFalse(CallDigestKakao.needsSound(message))
        assertTrue(CallDigestKakao.needsSound(CallDigestText.withUpdate(message,true)))
        assertTrue(CallDigestKakao.needsSound(CallDigestText.withLate(message,2)))
        assertTrue(CallDigestKakao.postMorning(app,CallDigestText.withLate(message,2)))
        val n=morning().single()
        assertEquals(CallDigestKakao.SENT_LINE,n.extras.getString(Notification.EXTRA_TEXT))
        assertNull("a ringing notice is in no quiet group",n.group)
        manager.cancelAll()
        assertTrue(CallDigestKakao.postMorning(app,message))
        val q=morning().single()
        assertEquals(CallDigestNotifier.QUIET_GROUP,q.group);assertEquals(Notification.GROUP_ALERT_SUMMARY,q.groupAlertBehavior)
        assertTrue(q.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().startsWith(CallDigestKakao.SENT_LINE+"\n"))
        // Not sent today: the notice as before, its own first line.
        manager.cancelAll()
        SecureSettings(app).update { it.put(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,System.currentTimeMillis()-30*h) }
        assertTrue(CallDigestKakao.postMorning(app,message))
        assertEquals(message.text,morning().single().extras.getString(Notification.EXTRA_TEXT));assertNull(morning().single().group)
    }

    // ---- M4 ----
    @Test fun aFailedTextIsRevivedTwiceInAllAndAnInterruptedOneOnce() {
        CallTranscriptionStore.buildCode={ 3000 }
        CallTranscriptionStore(app).use { s -> s.offer(candidate("x"),true,"");s.offer(candidate("i"),true,"");failOnce(s,"x");failOnce(s,"i","INTERRUPTED") }
        for(build in 3001..3004) {
            CallTranscriptionStore.buildCode={ build }
            CallTranscriptionStore(app).use { s ->
                val x=state(s,"x");val i=state(s,"i")
                when(build) {
                    3001 -> { assertEquals("QUEUED",x);assertEquals("QUEUED",i) }
                    3002 -> { assertEquals("QUEUED",x);assertEquals("the interrupted one once only","FAILED",i) }
                    else -> { assertEquals("two revivals in all","FAILED",x);assertEquals("FAILED",i) }
                }
                if(x=="QUEUED") failOnce(s,"x")
                if(i=="QUEUED") failOnce(s,"i","INTERRUPTED")
            }
        }
    }
    @Test fun anOlderTableGetsTheCountColumnAsNull() {
        CallTranscriptionStore.buildCode={ 4000 }
        CallTranscriptionStore(app).use { s -> s.offer(candidate("o"),true,"");failOnce(s,"o") }
        kr.co.callsummary.core.android.AndroidSqlPort(app,kr.callbrief.GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p ->
            assertTrue(p.query("PRAGMA table_info(call_transcription_jobs)").any { it["name"]=="requeue_count" })
            assertNull(p.query("SELECT requeue_count FROM call_transcription_jobs WHERE recording_id='o'").single()["requeue_count"])
        }
        CallTranscriptionStore.buildCode={ 4001 }
        CallTranscriptionStore(app).use { s -> assertEquals("QUEUED",state(s,"o")) }
    }

    // ---- L2 ----
    @Test fun theButtonsDayStaysWithinFiveYearsAndTomorrow() {
        val now=System.currentTimeMillis();val day=86_400_000L
        assertEquals(now-day,MainActivity.kakaoDay(now-day,now))
        assertEquals(now-4*365*day,MainActivity.kakaoDay(now-4*365*day,now))
        assertEquals(now+day,MainActivity.kakaoDay(now+day,now))
        assertEquals(now,MainActivity.kakaoDay(now-6*366*day,now))
        assertEquals(now,MainActivity.kakaoDay(now+3*day,now))
        assertEquals(now,MainActivity.kakaoDay(Long.MAX_VALUE,now))
        val far=MainActivity.kakaoTarget(Uri.parse("kakaoabc://kakaolink?screen=calls&dayMs=99999999999999"))!!
        assertEquals(Screen.CALLS,far.first);assertTrue(Math.abs(far.second!!-System.currentTimeMillis())<60_000)
    }

    // ---- L1 ----
    @Test fun withoutAKakaoKeyTheLoginWayBackIsOff() {
        val info=app.packageManager.getActivityInfo(android.content.ComponentName(app,"com.kakao.sdk.auth.AuthCodeHandlerActivity"),
            android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals("the test build has no key",kr.callbrief.BuildConfig.KAKAO_APP_KEY.isNotBlank(),info.enabled)
    }

    // ---- words ----
    @Test fun theSettingsWordsFollowTheQuietNotice() {
        val words=SettingsScreen.sendWords(KakaoReport.ALREADY_SENT)
        assertFalse(words,Regex("[A-Za-z·…]").containsMatchIn(words))
        assertFalse(CallDigestKakao.SENT_LINE.contains("·"))
    }
}
