package kr.callbrief.pipeline

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v3.7 (user 11/12): the app opens and a waiting new version installs by itself. The pure rules: when the open app starts
 * the install, when the "opened by the app" mark still opens the new version, when a session counts as unanswered, how often
 * the phone's install block is asked about, and the heartbeat's update keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OpenUpdateTest {
    private val base=OpenUpdate.Input(ready=true,downloading=false,autoInstall=true,inCall=false,declined=false,blocked=false,tried=false)
    private fun d(i:OpenUpdate.Input)=OpenUpdate.decide(i)

    @Test fun aWaitingVersionStartsAtOnceWithTheCover() {
        assertEquals(OpenUpdate.Decision(true,true,"start"),d(base))
    }
    @Test fun theSwitchOffKeepsTheButton() {
        assertEquals(OpenUpdate.Decision(false,false,"autoOff"),d(base.copy(autoInstall=false)))
        assertEquals("autoOff",d(base.copy(autoInstall=false,ready=false,downloading=true)).reason)
    }
    @Test fun nothingWaitingIsNothing() {
        assertEquals(OpenUpdate.Decision(false,false,"nothing"),d(base.copy(ready=false)))
    }
    @Test fun neverDuringACall() {
        assertEquals(OpenUpdate.Decision(false,false,"inCall"),d(base.copy(inCall=true)))
        assertEquals("inCall",d(base.copy(ready=false,downloading=true,inCall=true)).reason)
    }
    @Test fun aClosedWindowIsNotShownAgainThisOpening() {
        assertEquals(OpenUpdate.Decision(false,false,"declined"),d(base.copy(declined=true)))
        assertEquals(OpenUpdate.Decision(false,false,"tried"),d(base.copy(tried=true)))
    }
    @Test fun aBlockedInstallLeadsToTheQuestionNotAnotherTry() {
        assertEquals(OpenUpdate.Decision(false,false,"blocked"),d(base.copy(blocked=true,tried=true)))
    }
    @Test fun aDownloadIsFollowedUnderTheCover() {
        assertEquals(OpenUpdate.Decision(false,true,"downloading"),d(base.copy(ready=false,downloading=true)))
    }
    @Test fun oneSessionAtATime() {
        val fg=base.copy(tried=true,session=OpenUpdate.Session.FOREGROUND,sessionAgeMs=10_000)
        assertEquals(OpenUpdate.Decision(false,true,"inFlight"),d(fg))
        // A young background session may be installing without a tap: wait for it.
        val bg=base.copy(session=OpenUpdate.Session.BACKGROUND,sessionAgeMs=30_000)
        assertEquals(OpenUpdate.Decision(false,true,"bgFresh"),d(bg))
        // One that waits for a tap, or is old, gives way to the foreground one (install() abandons it first).
        assertEquals(OpenUpdate.Decision(true,true,"replace"),d(bg.copy(sessionWaitsTap=true)))
        assertEquals(OpenUpdate.Decision(true,true,"replace"),d(bg.copy(sessionAgeMs=OpenUpdate.BACKGROUND_FRESH_MS)))
        // A foreground session of an earlier opening, never answered: replaced too.
        assertEquals(OpenUpdate.Decision(true,true,"replace"),d(base.copy(session=OpenUpdate.Session.FOREGROUND,sessionAgeMs=OpenUpdate.FOREGROUND_WAIT_MS)))
    }
    @Test fun theRelaunchMarkLastsTenMinutes() {
        val t=1_000_000L
        assertTrue(OpenUpdate.relaunchDue(t,t))
        assertTrue(OpenUpdate.relaunchDue(t,t+OpenUpdate.RELAUNCH_MS))
        assertFalse(OpenUpdate.relaunchDue(t,t+OpenUpdate.RELAUNCH_MS+1))
        assertFalse("no mark",OpenUpdate.relaunchDue(0,t))
        assertFalse("a mark from the future (clock moved)",OpenUpdate.relaunchDue(t,t-1))
        assertEquals(10*60_000L,OpenUpdate.RELAUNCH_MS)
    }
    @Test fun noAnswerAfterTwoMinutesInFrontAndHalfAnHourBehind() {
        assertFalse(OpenUpdate.noAnswer(OpenUpdate.Session.FOREGROUND,OpenUpdate.FOREGROUND_WAIT_MS-1,false))
        assertTrue(OpenUpdate.noAnswer(OpenUpdate.Session.FOREGROUND,OpenUpdate.FOREGROUND_WAIT_MS,false))
        assertFalse(OpenUpdate.noAnswer(OpenUpdate.Session.BACKGROUND,29*60_000L,false))
        assertTrue(OpenUpdate.noAnswer(OpenUpdate.Session.BACKGROUND,30*60_000L,false))
        assertFalse("a tap was asked: that is an answer",OpenUpdate.noAnswer(OpenUpdate.Session.BACKGROUND,3_600_000L,true))
        assertFalse(OpenUpdate.noAnswer(OpenUpdate.Session.NONE,3_600_000L,false))
    }
    @Test fun theBlockQuestionComesTwiceAtMostAndNeverAfterLater() {
        assertTrue(OpenUpdate.askBlocked(samsung=true,asksThisOpen=0,later=false))
        assertTrue("once more after the settings",OpenUpdate.askBlocked(true,1,false))
        assertFalse("then that opening is over",OpenUpdate.askBlocked(true,2,false))
        assertFalse("나중에",OpenUpdate.askBlocked(true,0,true))
        assertTrue(OpenUpdate.askBlocked(false,0,false))
        assertFalse("other phones: their line once",OpenUpdate.askBlocked(false,1,false))
        assertTrue(OpenUpdate.samsung("samsung"));assertTrue(OpenUpdate.samsung("Samsung"));assertFalse(OpenUpdate.samsung("Google"))
    }
    @Test fun theMarksBelongToOneOpening() {
        OpenUpdate.reset()
        OpenUpdate.opened();OpenUpdate.noteDeclined(1051)
        assertTrue(OpenUpdate.declined(1051));assertFalse("another version",OpenUpdate.declined(1052))
        OpenUpdate.opened()
        assertFalse("the next opening tries once more",OpenUpdate.declined(1051))
        OpenUpdate.noteBlocked(1051);OpenUpdate.noteTried(1051);OpenUpdate.noteAsked()
        assertTrue(OpenUpdate.blocked(1051))
        assertFalse("no settings opened: no retry",OpenUpdate.takeRetry())
        OpenUpdate.noteSettingsOpened()
        assertTrue(OpenUpdate.takeRetry())
        assertFalse(OpenUpdate.blocked(1051));assertFalse(OpenUpdate.tried(1051))
        assertFalse("once",OpenUpdate.takeRetry())
        assertEquals(1,OpenUpdate.asksThisOpen())
        OpenUpdate.opened();assertEquals(0,OpenUpdate.asksThisOpen())
        OpenUpdate.reset()
    }
    @Test fun aSamsungVerificationRefusalIsABlockNotAClosedWindow() {
        val P=android.content.pm.PackageInstaller::class.java
        val blocked=P.getField("STATUS_FAILURE_BLOCKED").getInt(null);val aborted=P.getField("STATUS_FAILURE_ABORTED").getInt(null)
        val failure=P.getField("STATUS_FAILURE").getInt(null);val success=P.getField("STATUS_SUCCESS").getInt(null)
        val msg="INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed"
        assertTrue(OpenUpdate.blockedBy(blocked,null,false))
        assertTrue(OpenUpdate.blockedBy(failure,msg,true))
        assertTrue(OpenUpdate.blockedBy(aborted,msg,true))
        assertTrue(OpenUpdate.blockedBy(failure,"Install not allowed",true))
        assertFalse("the person closed the window",OpenUpdate.blockedBy(aborted,"User rejected permissions",true))
        assertFalse("not Samsung: only the status",OpenUpdate.blockedBy(failure,msg,false))
        assertFalse(OpenUpdate.blockedBy(success,msg,true))
        assertEquals("INSTALL_FAILED_VERIFICATION_FAILURE",OpenUpdate.messageCode(msg))
        assertEquals("",OpenUpdate.messageCode("Install not allowed"));assertEquals("",OpenUpdate.messageCode(null))
    }
    @Test fun behindTheSettingsItTriesEveryFiveSecondsForTwoMinutesOneAtATime() {
        fun st(now:Long,last:Long=0,pending:Boolean=false,front:Boolean=false,later:Boolean=false,inCall:Boolean=false,taken:Boolean=false)=
            OpenUpdate.retryStep(1_000L,now,last,pending,front,later,inCall,taken)
        assertEquals(OpenUpdate.RetryStep(OpenUpdate.RetryAct.TRY,"try"),st(1_000L))
        assertEquals("one session at a time",OpenUpdate.RetryAct.WAIT,st(10_000L,last=2_000L,pending=true).act)
        assertEquals("interval",st(5_000L,last=1_000L).reason)
        assertEquals(OpenUpdate.RetryAct.TRY,st(6_000L,last=1_000L).act)
        assertEquals("timeout",st(1_000L+OpenUpdate.RETRY_FOR_MS).reason)
        assertEquals("timeout before waiting",st(1_000L+OpenUpdate.RETRY_FOR_MS,pending=true).reason)
        assertEquals("back in front: onResume goes on","front",st(2_000L,front=true).reason)
        assertEquals("later",st(2_000L,later=true).reason);assertEquals("inCall",st(2_000L,inCall=true).reason)
        assertEquals("taken",st(2_000L,taken=true).reason)
        assertEquals(5_000L,OpenUpdate.RETRY_EVERY_MS);assertEquals(120_000L,OpenUpdate.RETRY_FOR_MS)
    }
    @Test fun theRetryBelongsToTheOpeningWithSettingsOpened() {
        OpenUpdate.reset();OpenUpdate.opened()
        assertFalse(OpenUpdate.retryPending())
        OpenUpdate.noteSettingsOpened();assertTrue(OpenUpdate.retryPending())
        assertTrue(OpenUpdate.takeRetry());assertFalse("used once",OpenUpdate.retryPending())
        OpenUpdate.noteSettingsOpened();OpenUpdate.opened();assertFalse("a new opening",OpenUpdate.retryPending())
        OpenUpdate.reset()
    }
    @Test fun theCoverShowsThePercentWhenThereIsOne() {
        assertEquals("최신 버전으로 업데이트하는 중이에요 42%",OpenUpdate.coverTitle(42))
        assertEquals(OpenUpdate.UPDATING_WORDS,OpenUpdate.coverTitle(-1))
        assertEquals(0,OpenUpdate.percentOf(-0.1f));assertEquals(80,OpenUpdate.percentOf(0.8f));assertEquals(100,OpenUpdate.percentOf(1.2f))
    }
    @Test fun theWordsKeepTheScreenRules() {
        for(w in listOf(OpenUpdate.UPDATING_WORDS,OpenUpdate.UPDATING_NOTE,OpenUpdate.BLOCK_ASK_WORDS,OpenUpdate.BLOCK_ASK_PATH,OpenUpdate.BLOCK_ASK_AGAIN,OpenUpdate.BLOCK_ASK_AUTO,OpenUpdate.BLOCK_ASK_TITLE,
                OpenUpdate.OTHER_BLOCKED_WORDS,OpenUpdate.SAMSUNG_NO_ANSWER_WORDS,OpenUpdate.OTHER_NO_ANSWER_WORDS,OpenUpdate.DONE_TITLE)) {
            assertFalse(w,w.contains("…") || w.contains("...") || w.contains("·"))
        }
        assertTrue(OpenUpdate.BLOCK_ASK_WORDS.contains("보안 위험 자동 차단"))
    }

    @Test fun theHeartbeatSaysWhyTheBackgroundInstallDidNotHappen() {
        val base=PhoneStatus.Input(1_000L,"v3.7",1060,0,null,5,6,"READY","","",12)
        val empty=PhoneStatus.build(base).getJSONObject("update")
        for(k in listOf("error","bg","bgAtMs","sessionOpen","autoInstall","open")) assertTrue(k,empty.has(k) && empty.isNull(k))
        assertEquals(0,empty.getLong("openInstalls"));assertEquals(0,empty.getLong("blockAsk"));assertEquals(0,empty.getLong("blockOpen"))
        val s=org.json.JSONObject().put(PipelineSettingsKeys.UPDATE_BG,"exception:SecurityException").put(PipelineSettingsKeys.UPDATE_BG_AT_MS,900L)
            .put(PipelineSettingsKeys.AUTO_INSTALL,false).put(PipelineSettingsKeys.UPDATE_OPEN_INSTALLS,2).put(PipelineSettingsKeys.UPDATE_OPEN,"inCall")
            .put(PipelineSettingsKeys.UPDATE_BLOCK_ASK,2).put(PipelineSettingsKeys.UPDATE_BLOCK_OPEN,1)
        val u=PhoneStatus.build(base.copy(v34=PhoneStatus.v34(s).copy(sessionOpen=true))).getJSONObject("update")
        assertEquals("exception:SecurityException",u.getString("bg"));assertEquals(900L,u.getLong("bgAtMs"))
        assertTrue(u.getBoolean("sessionOpen"));assertFalse(u.getBoolean("autoInstall"))
        assertEquals(2,u.getLong("openInstalls"));assertEquals("inCall",u.getString("open"))
        assertEquals(2,u.getLong("blockAsk"));assertEquals(1,u.getLong("blockOpen"))
        assertTrue(u.isNull("blockStatus"));assertTrue(u.isNull("autoBack"))
        val back=PhoneStatus.build(base.copy(v34=PhoneStatus.v34(org.json.JSONObject().put(PipelineSettingsKeys.UPDATE_BLOCK_STATUS,2)
            .put(PipelineSettingsKeys.UPDATE_AUTO_BACK,"manual")))).getJSONObject("update")
        assertEquals(2,back.getInt("blockStatus"));assertEquals("manual",back.getString("autoBack"))
        assertEquals(0,u.getLong("blockRetries"))
        assertEquals(3,PhoneStatus.build(base.copy(v34=PhoneStatus.v34(org.json.JSONObject().put(PipelineSettingsKeys.UPDATE_BLOCK_RETRIES,3)))).getJSONObject("update").getLong("blockRetries"))
        assertTrue("no check error, nothing blocked",u.isNull("error"))
        // A blocked install shows in update.error too (a check error goes first).
        val blocked=PhoneStatus.build(base.copy(v34=PhoneStatus.v34(org.json.JSONObject().put(PipelineSettingsKeys.UPDATE_BG,"blocked")))).getJSONObject("update")
        assertEquals("blocked",blocked.getString("error"))
        val open=PhoneStatus.build(base.copy(v34=PhoneStatus.v34(org.json.JSONObject().put(PipelineSettingsKeys.UPDATE_OPEN,"noAnswer")))).getJSONObject("update")
        assertEquals("noAnswer",open.getString("error"))
        val both=PhoneStatus.build(base.copy(v34=PhoneStatus.v34(org.json.JSONObject().put(PipelineSettingsKeys.UPDATE_BG,"blocked").put(PipelineSettingsKeys.UPDATE_ERROR_CODE,"HTTP_404")))).getJSONObject("update")
        assertEquals("HTTP_404",both.getString("error"))
        assertTrue("on by default",PhoneStatus.v34(org.json.JSONObject()).autoInstall==true)
    }
}
