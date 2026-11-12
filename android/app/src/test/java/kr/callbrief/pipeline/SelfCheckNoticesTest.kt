package kr.callbrief.pipeline

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** Round 6: the evening self-check notices (design B4), the report's one try at fresh results, and the update failure words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SelfCheckNoticesTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val h=3_600_000L
    /** 19:00 KST on 11/4, when the evening report runs. */
    private val now=LocalDate.of(2026,11,4).atTime(19,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private val fine=SelfCheckNotices.Input(now,folderChosen=true,folderReadable=true,modelState="READY",
        queued=0,oldestQueuedMs=null,lastTranscribedMs=now-h,sending=true,oldestWaitingMs=null,lastAnalyzedMs=now-h,dbBytes=1024L)
    private fun codes(i:SelfCheckNotices.Input)=SelfCheckNotices.check(i).map { it.code }

    @Test fun nothingIsToldWhenAllIsWell() {
        assertEquals(emptyList<String>(),codes(fine))
        assertNull(SelfCheckNotices.message(emptyList()))
    }

    @Test fun eachProblemIsFoundOnItsOwn() {
        // Text is late: recordings wait over 6 hours and nothing was turned into text for 6 hours.
        assertEquals(listOf(SelfCheckNotices.TRANSCRIBE_LATE),codes(fine.copy(queued=3,oldestQueuedMs=now-8*h,lastTranscribedMs=now-7*h)))
        // A new recording, or one done an hour ago, is no backlog.
        assertEquals(emptyList<String>(),codes(fine.copy(queued=1,oldestQueuedMs=now-h,lastTranscribedMs=now-30*h)))
        assertEquals(emptyList<String>(),codes(fine.copy(queued=3,oldestQueuedMs=now-8*h,lastTranscribedMs=now-h)))
        assertEquals(listOf(SelfCheckNotices.MODEL_FAILED),codes(fine.copy(modelState="FAILED",queued=3,oldestQueuedMs=now-8*h,lastTranscribedMs=now-7*h)))
        assertEquals(listOf(SelfCheckNotices.FOLDER_LOST),codes(fine.copy(folderReadable=false)))
        // Never chosen is the first-run setup, not a lost connection.
        assertEquals(emptyList<String>(),codes(fine.copy(folderChosen=false,folderReadable=false)))
        assertEquals(listOf(SelfCheckNotices.NO_RESULT),codes(fine.copy(oldestWaitingMs=now-4*h,lastAnalyzedMs=now-13*h)))
        assertEquals(emptyList<String>(),codes(fine.copy(oldestWaitingMs=now-h,lastAnalyzedMs=now-13*h)))
        assertEquals(emptyList<String>(),codes(fine.copy(sending=false,oldestWaitingMs=now-4*h,lastAnalyzedMs=now-13*h)))
        val limit=CallRecordStore.DB_LIMIT_BYTES
        assertEquals(listOf(SelfCheckNotices.STORAGE_FULL),codes(fine.copy(dbBytes=limit*81/100)))
        assertEquals(emptyList<String>(),codes(fine.copy(dbBytes=limit*79/100)))
        assertTrue(SelfCheckNotices.check(fine.copy(dbBytes=limit*86/100)).single().text.contains("85%"))
    }

    @Test fun theLateResultNoticeDoesNotRepeatTheStallOrDisconnectNotice() {
        val late=fine.copy(oldestWaitingMs=now-7*h,lastAnalyzedMs=now-13*h)
        assertEquals(emptyList<String>(),codes(late.copy(stallWarnedDay="2026-11-04")))
        assertEquals(emptyList<String>(),codes(late.copy(disconnectWarnedDay="2026-11-04")))
        assertEquals(listOf(SelfCheckNotices.NO_RESULT),codes(late.copy(stallWarnedDay="2026-11-03")))
    }

    @Test fun theSameProblemIsToldOnceADay() {
        val found=SelfCheckNotices.check(fine.copy(folderReadable=false,dbBytes=CallRecordStore.DB_LIMIT_BYTES))
        val s=JSONObject()
        assertEquals(2,SelfCheckNotices.due(found,now,s).size)
        SelfCheckNotices.remember(s,SelfCheckNotices.due(found,now,s),now)
        assertEquals(emptyList<SelfCheckNotices.Notice>(),SelfCheckNotices.due(found,now+h,s))
        // A new problem the same day still comes through; the next day everything is told again.
        val model=SelfCheckNotices.check(fine.copy(modelState="FAILED"))
        assertEquals(listOf(SelfCheckNotices.MODEL_FAILED),SelfCheckNotices.due(found+model,now+h,s).map { it.code })
        SelfCheckNotices.remember(s,model,now+h)
        assertEquals(setOf(SelfCheckNotices.FOLDER_LOST,SelfCheckNotices.STORAGE_FULL,SelfCheckNotices.MODEL_FAILED),s.getString(SelfCheckNotices.SHOWN_KEY).split(',').toSet())
        assertEquals(3,SelfCheckNotices.due(found+model,now+24*h,s).size)
    }

    @Test fun theWordsArePlainKorean() {
        val all=SelfCheckNotices.check(SelfCheckNotices.Input(now,true,false,"FAILED",0,null,0,true,now-5*h,now-20*h,CallRecordStore.DB_LIMIT_BYTES))
        assertEquals(listOf(SelfCheckNotices.MODEL_FAILED,SelfCheckNotices.FOLDER_LOST,SelfCheckNotices.NO_RESULT,SelfCheckNotices.STORAGE_FULL),all.map { it.code })
        val late=SelfCheckNotices.check(fine.copy(queued=2,oldestQueuedMs=now-9*h,lastTranscribedMs=now-9*h))
        for(n in all+late) {
            val words=n.title+n.text
            assertFalse(words,Regex("[A-Za-z·･…]").containsMatchIn(words) || words.contains("..."))
        }
        assertEquals("통화 글자 바꾸기가 밀려 있어요",late.single().title)
        // Nobody else is told yet (the watchdog is not installed): no notice may say so.
        assertTrue(all.none { it.text.contains("알렸어요") })
        assertEquals("통화 정리 결과가 오래 오지 않아요. 휴대폰이 인터넷에 연결되어 있으면 매시간 다시 받아 봐요.",all.single { it.code==SelfCheckNotices.NO_RESULT }.text)
        val one=SelfCheckNotices.message(late)!!
        assertEquals("통화 글자 바꾸기가 밀려 있어요",one.title);assertEquals("충전기에 꽂아 두면 밤에 끝나요.",one.text)
        val many=SelfCheckNotices.message(all)!!
        assertEquals("이음에서 살펴볼 일이 4가지 있어요",many.title)
        assertEquals("음성 인식 모델을 다시 받아야 해요. 인터넷에 연결된 상태에서 이음 설정을 열어 주세요.",many.lines.first())
        assertEquals(4,many.lines.size)
    }

    @Test fun theSelfCheckNoticeGoesToTheStatusChannelAndOpensSettings() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(CallDigestNotifier.post(app,CallDigestWork.SELF_CHECK,CallDigestText.Message("녹음 폴더 연결이 풀렸어요","이음 설정에서 녹음 폴더를 다시 골라 주세요.")))
        CallDigestNotifier.post(app,CallDigestWork.STATUS,CallDigestText.Message("정리가 멈췄어요","통화가 6시간 넘게 정리되지 않고 있어요."))
        val m=app.getSystemService(android.app.NotificationManager::class.java)
        val posted=shadowOf(m).allNotifications
        // Its own slot: the stall notice from the same day stays.
        assertEquals(2,posted.size);assertTrue(posted.all { it.channelId==CallDigestWork.STATUS_CHANNEL })
        val self=posted.single { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)=="녹음 폴더 연결이 풀렸어요" }
        val open=shadowOf(self.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name,open.component?.className);assertEquals("SETTINGS",open.getStringExtra(MainActivity.EXTRA_SCREEN))
    }

    @Test fun notificationsOffMeansNothingWasPosted() {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(CallDigestNotifier.post(app,CallDigestWork.SELF_CHECK,CallDigestText.Message("제목","본문")))
    }

    // ---- the report's one try at fresh results ----
    @Test fun thePrefetchWaitsAtMostItsLimitAndNeverThrows() {
        assertEquals(CallDigestPrefetch.Outcome.DONE,CallDigestPrefetch.run(1_000) { true })
        assertEquals(CallDigestPrefetch.Outcome.FAILED,CallDigestPrefetch.run(1_000) { false })
        assertEquals(CallDigestPrefetch.Outcome.FAILED,CallDigestPrefetch.run(1_000) { throw java.io.IOException("offline") })
        val started=System.nanoTime()
        assertEquals(CallDigestPrefetch.Outcome.TIMED_OUT,CallDigestPrefetch.run(200) { Thread.sleep(5_000);true })
        assertTrue((System.nanoTime()-started)/1_000_000<2_000)
        assertEquals(3*60_000L,CallDigestPrefetch.TIMEOUT_MS) // v3.1 (DESIGN_V4 2.1-5): at most 3 minutes
    }

    // ---- update failure words ----
    @Test fun updateFailuresReadAsKoreanAndACancelIsNoError() {
        assertNull(CallUpdate.errorWords(""));assertNull(CallUpdate.errorWords(null))
        assertNull(CallUpdate.errorWords("STATUS_FAILURE_ABORTED (3): User rejected permissions"))
        assertEquals("저장 공간이 부족해 설치하지 못했어요.",CallUpdate.errorWords("STATUS_FAILURE_STORAGE (6): not enough space")!!.reason)
        assertEquals("설치가 중간에 멈췄어요. 홈에서 지금 업데이트를 다시 눌러 주세요.",CallUpdate.errorWords("STATUS_FAILURE_TIMEOUT (8)")!!.sentence)
        assertEquals("설치가 중간에 멈췄어요.",CallUpdate.errorWords("STATUS_FAILURE (1)")!!.reason)
        assertEquals("설치가 중간에 멈췄어요.",CallUpdate.errorWords("something odd")!!.reason)
        assertEquals("휴대폰이 설치를 막았어요.",CallUpdate.errorWords("STATUS_FAILURE_BLOCKED (2)")!!.reason)
        for(c in listOf("STATUS_FAILURE_CONFLICT (5)","STATUS_FAILURE_INCOMPATIBLE (7): 서명이 달라요","STATUS_FAILURE_INVALID (4)"))
            assertEquals("새 버전이 이 휴대폰에 맞지 않아 설치하지 못했어요.",CallUpdate.errorWords(c)!!.reason)
        for(c in listOf("STATUS_FAILURE_STORAGE (6): x","STATUS_FAILURE (1)","STATUS_FAILURE_BLOCKED (2)","STATUS_FAILURE_INVALID (4)"))
            assertFalse(Regex("[A-Za-z·…]").containsMatchIn(CallUpdate.errorWords(c)!!.sentence))
        assertTrue(CallUpdate.isUserCancel(PackageInstaller.STATUS_FAILURE_ABORTED));assertFalse(CallUpdate.isUserCancel(PackageInstaller.STATUS_FAILURE_STORAGE))
    }

    @Test fun aCancelledInstallWindowIsNotKeptAsAnError() {
        CallUpdate.saveError(app,null)
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE_ABORTED).putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,"User rejected permissions"))
        repeat(20) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
        assertEquals("",SecureSettings(app).snapshot().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR))
    }

    /** Review data #4 / UI #9: the store share is measured on the real sealed file (the old path never existed). */
    @Test fun storageShareIsReadFromTheSealedFileAndShownInSettings() {
        PreviewSeed.ensure(app)
        val sealed=kr.co.callsummary.core.android.ProtectedDatabase.file(app,kr.callbrief.GeneralLocalStore.DATABASE)
        assertTrue(sealed.isFile);assertFalse(app.getDatabasePath(kr.callbrief.GeneralLocalStore.DATABASE).isFile)
        val store=StatusLights.read(app,SecureSettings(app).snapshot(),now)
        assertEquals(sealed.length(),store.dbBytes);assertTrue((store.dbRows ?: 0)>0);assertNotNull(store.dbPercent)
        assertNotNull(store.queue);assertNotNull(store.failed)
        // The self-check gets the same numbers: the 80% notice can ring now.
        val full=SelfCheckNotices.check(fine.copy(dbBytes=null,dbRows=StatusLights.ROW_LIMIT*85/100))
        assertEquals(listOf(SelfCheckNotices.STORAGE_FULL),full.map { it.code })
        val a=launchMain(Screen.SETTINGS)
        fun find(t:String)=a.window.decorView.findViewWithTag<View>(t)
        repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(find("storage_use")!=null) return@repeat;Thread.sleep(10) }
        val line=(find("storage_use") as TextView).text.toString()
        assertTrue(line,Regex("""저장 공간 \d+% 사용""").matches(line))
    }

    @Test fun settingsShowNoAmberForAStoredCancel() {
        PreviewSeed.ensure(app)
        SecureSettings(app).update { it.put(PipelineSettingsKeys.LAST_UPDATE_ERROR,"STATUS_FAILURE_ABORTED (3): User rejected permissions") }
        val a=launchMain(Screen.SETTINGS)
        fun find(t:String)=a.window.decorView.findViewWithTag<View>(t)
        repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(find("status_update")!=null) return@repeat;Thread.sleep(10) }
        assertNotNull(find("status_update"));assertNull(find("update_error"))
        fun texts(v:View):List<String> =(if(v is TextView) listOf(v.text.toString()) else emptyList())+if(v is ViewGroup)(0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()
        assertTrue(texts(find("status_update")).any { it=="정상" || it.contains("새 버전이 준비됐어요") })
        assertTrue(texts(a.window.decorView).none { it.contains("STATUS") || it.contains("User rejected") })
    }
}
