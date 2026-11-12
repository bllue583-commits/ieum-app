package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kr.callbrief.R

/** v3.7 (user 11/12): the app opens and a waiting new version installs by itself, "최신 버전으로 업데이트하는 중이에요" on
 * screen, then the new version opens again. No button to find. [decide] is the pure rule; the rest is this process's memory
 * of what happened while the app was open (nothing of it is personal, it is never written anywhere but the heartbeat codes). */
internal object OpenUpdate {
    /** The install session that is open now, as far as the decision needs it. */
    enum class Session { NONE, FOREGROUND, BACKGROUND }
    data class Input(val ready:Boolean,val downloading:Boolean,val autoInstall:Boolean,val inCall:Boolean,
        /** The user closed the system install window for this version since the app was opened. */
        val declined:Boolean,
        /** The phone blocked the install of this version since the app was opened (the guide is shown instead). */
        val blocked:Boolean,
        /** An install of this version was already started by opening the app this time. */
        val tried:Boolean,
        val session:Session=Session.NONE,val sessionAgeMs:Long=0,
        /** Android answered the background session with "wants a tap" (its notification is or was there). */
        val sessionWaitsTap:Boolean=false)
    /** [start]: begin the foreground install now. [cover]: show the "업데이트하는 중" screen. [reason]: a code for the heartbeat. */
    data class Decision(val start:Boolean,val cover:Boolean,val reason:String)

    /** A foreground session younger than this is still waiting for its answer (or its window): no second one. */
    const val FOREGROUND_WAIT_MS=2*60_000L
    /** A background session younger than this, not answered yet, may be installing right now without a tap: wait for it. */
    const val BACKGROUND_FRESH_MS=2*60_000L
    /** A background session without any answer for this long is reported as `noAnswer`. */
    const val BACKGROUND_NO_ANSWER_MS=30*60_000L
    /** The "opened by the app" mark is good for this long; an install that ends later does not open the app again. */
    const val RELAUNCH_MS=10*60_000L

    fun decide(i:Input):Decision = when {
        !i.autoInstall -> Decision(false,false,"autoOff")
        !i.ready && !i.downloading -> Decision(false,false,"nothing")
        i.inCall -> Decision(false,false,"inCall")
        i.declined -> Decision(false,false,"declined")
        i.blocked -> Decision(false,false,"blocked")
        !i.ready -> Decision(false,true,"downloading")
        i.session==Session.FOREGROUND && i.sessionAgeMs<FOREGROUND_WAIT_MS -> Decision(false,true,"inFlight")
        i.tried -> Decision(false,false,"tried")
        i.session==Session.BACKGROUND && !i.sessionWaitsTap && i.sessionAgeMs<BACKGROUND_FRESH_MS -> Decision(false,true,"bgFresh")
        i.session!=Session.NONE -> Decision(true,true,"replace")
        else -> Decision(true,true,"start")
    }
    /** The mark left before an install the open app started ([markMs]) is still good at [now]. */
    fun relaunchDue(markMs:Long,now:Long):Boolean = markMs>0 && now>=markMs && now-markMs<=RELAUNCH_MS
    /** An open session that never got an answer: a foreground one after [FOREGROUND_WAIT_MS], a background one after
     * [BACKGROUND_NO_ANSWER_MS]. */
    fun noAnswer(session:Session,ageMs:Long,answered:Boolean):Boolean = !answered && when(session) {
        Session.NONE -> false
        Session.FOREGROUND -> ageMs>=FOREGROUND_WAIT_MS
        Session.BACKGROUND -> ageMs>=BACKGROUND_NO_ANSWER_MS
    }

    // ---- what happened since the app was opened (this process only) ----
    /** Counts the times the app was opened anew ([MainActivity.onCreate] without saved state). */
    @Volatile var openId=0
        private set
    fun opened() { openId++ }
    private class Mark(val openId:Int,val code:Int)
    @Volatile private var declinedMark:Mark?=null
    @Volatile private var blockedMark:Mark?=null
    @Volatile private var triedMark:Mark?=null
    /** True while [MainActivity] is in front; a finished download is then installed by the screen, not in the background. */
    @Volatile var front=false
    /** Called (on the main thread) when a download finished while the app is in front. */
    @Volatile var readyListener:(()->Unit)?=null
    fun declined(code:Int)=declinedMark?.let { it.openId==openId && it.code==code }==true
    fun blocked(code:Int)=blockedMark?.let { it.openId==openId && it.code==code }==true
    fun tried(code:Int)=triedMark?.let { it.openId==openId && it.code==code }==true
    fun noteDeclined(code:Int) { declinedMark=Mark(openId,code) }
    fun noteBlocked(code:Int) { blockedMark=Mark(openId,code) }
    fun noteTried(code:Int) { triedMark=Mark(openId,code) }
    /** [다시 설치] on the guide: the person asks for it, so the block of this open is over. */
    fun clearBlocked() { blockedMark=null;triedMark=null }
    // ---- v3.7: [BlockRetry], the app's own install tried again while it is behind the settings ----
    enum class RetryAct { TRY, WAIT, STOP }
    data class RetryStep(val act:RetryAct,val reason:String)
    /** One try every this often (after the last try's answer came). */
    const val RETRY_EVERY_MS=5_000L
    /** Tries for at most this long after the app went behind the settings. */
    const val RETRY_FOR_MS=2*60_000L
    /** What the loop does now: stop when the app is in front again (onResume goes on), after [나중에], when a call starts, once
     * a try was taken (not blocked) or after [RETRY_FOR_MS]; wait while a try's answer is still coming (one session at a time)
     * or [RETRY_EVERY_MS] has not passed since the last try; else try. */
    fun retryStep(startMs:Long,now:Long,lastTryMs:Long,pending:Boolean,front:Boolean,later:Boolean,inCall:Boolean,taken:Boolean):RetryStep = when {
        front -> RetryStep(RetryAct.STOP,"front")
        taken -> RetryStep(RetryAct.STOP,"taken")
        later -> RetryStep(RetryAct.STOP,"later")
        inCall -> RetryStep(RetryAct.STOP,"inCall")
        now-startMs>=RETRY_FOR_MS -> RetryStep(RetryAct.STOP,"timeout")
        pending -> RetryStep(RetryAct.WAIT,"pending")
        lastTryMs>0 && now-lastTryMs<RETRY_EVERY_MS -> RetryStep(RetryAct.WAIT,"interval")
        else -> RetryStep(RetryAct.TRY,"try")
    }
    /** For tests. */
    internal fun reset() { declinedMark=null;blockedMark=null;triedMark=null;front=false;readyListener=null;askOpen=-1;asks=0;later=false;retryOpen=-1;BlockRetry.resetForTest() }

    // ---- the phone blocked the install (Samsung: 보안 위험 자동 차단, user 11/12) ----
    /** The question "설정을 열까요?" comes at most this often per opening of the app: once, and once more after the person
     * came back from the settings and it was blocked again. Other phones get their one line once. */
    const val BLOCK_ASKS_SAMSUNG=2;const val BLOCK_ASKS_OTHER=1
    /** Whether to ask now: not after [나중에] this opening, and not more than [BLOCK_ASKS_SAMSUNG] (others [BLOCK_ASKS_OTHER]). */
    fun askBlocked(samsung:Boolean,asksThisOpen:Int,later:Boolean):Boolean = !later && asksThisOpen<(if(samsung) BLOCK_ASKS_SAMSUNG else BLOCK_ASKS_OTHER)
    @Volatile private var askOpen=-1;@Volatile private var asks=0;@Volatile private var later=false;@Volatile private var retryOpen=-1
    private fun sameOpen() { if(askOpen!=openId) { askOpen=openId;asks=0;later=false } }
    fun asksThisOpen():Int { sameOpen();return asks }
    fun laterThisOpen():Boolean { sameOpen();return later }
    fun noteAsked() { sameOpen();asks++ }
    fun noteLater() { sameOpen();later=true }
    /** [설정 열기]: when the person comes back to the app, the install is tried once more by itself. */
    fun noteSettingsOpened() { retryOpen=openId }
    /** True once after [noteSettingsOpened] in this opening: the blocked mark goes, so [decide] starts again. */
    /** [설정 열기] was pressed in this opening and its retry is not used yet ([BlockRetry] starts when the app goes behind). */
    fun retryPending()=retryOpen==openId
    fun takeRetry():Boolean { if(retryOpen!=openId) return false;retryOpen=-1;clearBlocked();return true }
    /** The phone blocked the install: STATUS_FAILURE_BLOCKED anywhere, and on a Samsung phone also a failure whose message says
     * the verification refused it ("INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed", seen with Auto Blocker on), even
     * when it comes as STATUS_FAILURE or STATUS_FAILURE_ABORTED. Checked before "the person closed the window". */
    fun blockedBy(status:Int,message:String?,samsung:Boolean):Boolean {
        if(status==android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED) return true
        if(!samsung || status==android.content.pm.PackageInstaller.STATUS_SUCCESS || status==android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) return false
        val m=message.orEmpty()
        return m.contains("VERIFICATION_FAILURE",ignoreCase=true) || m.contains("Install not allowed",ignoreCase=true)
    }
    /** The code part of the installer's message ("INSTALL_FAILED_VERIFICATION_FAILURE"), never its words; "" when there is none. */
    fun messageCode(message:String?):String = Regex("[A-Z][A-Z0-9_]{5,59}").find(message.orEmpty())?.value.orEmpty()
    const val BLOCK_ASK_TITLE="보안 위험 자동 차단을 잠시 꺼 주세요"
    const val BLOCK_ASK_WORDS="새 버전을 설치하려면 휴대폰의 '보안 위험 자동 차단'을 잠시 꺼야 해요. 설정을 열까요?"
    const val BLOCK_ASK_PATH="설정 › 보안 및 개인정보 보호 › 보안 위험 자동 차단에서 끌 수 있어요."
    const val BLOCK_ASK_AUTO="스위치를 끄면 잠시 뒤 이음이 알아서 업데이트하고 다시 열려요."
    const val BLOCK_ASK_AGAIN="설정에 '자동으로 다시 켜기'가 있으면 꺼 두세요. 그러면 다음부터는 묻지 않고 알아서 설치해요."
    const val OTHER_BLOCKED_TITLE="새 버전을 설치하지 못했어요"

    /** Any sign of a call: the call state the app follows, the call card (shown or its service up), or the phone itself. */
    fun inCall(c:Context):Boolean = runCatching {
        kr.callbrief.pipeline.call.CallSession.current().state!=kr.callbrief.pipeline.call.CallSession.State.IDLE ||
            kr.callbrief.pipeline.call.CallCardService.driver.shown || kr.callbrief.pipeline.call.CallCardService.running!=null ||
            kr.callbrief.pipeline.call.CallCardService.inCall(c.applicationContext)
    }.getOrDefault(false)

    /** Samsung phones (One UI): a blocked install is most likely "보안 위험 자동 차단" (Auto Blocker). There is no public way to
     * read that switch, so only the installer's answer (STATUS_FAILURE_BLOCKED, or no answer) leads here. */
    fun samsung(manufacturer:String=Build.MANUFACTURER)=manufacturer.equals("samsung",ignoreCase=true)
    const val OTHER_BLOCKED_WORDS="휴대폰이 설치를 막았어요. 관리하는 분께 알려 주세요."
    const val NO_ANSWER_TITLE="새 버전 설치가 끝나지 않았어요"
    const val SAMSUNG_NO_ANSWER_WORDS="휴대폰의 '보안 위험 자동 차단'이 막고 있을 수 있어요. 설정 › 보안 및 개인정보 보호 › 보안 위험 자동 차단을 잠시 끈 뒤 다시 설치해 주세요."
    const val OTHER_NO_ANSWER_WORDS="다시 설치를 눌러 한 번 더 해 볼게요."
    const val UPDATING_WORDS="최신 버전으로 업데이트하는 중이에요."
    /** "최신 버전으로 업데이트하는 중이에요 42%"; without a percent the plain sentence (the bar then moves on its own). */
    fun coverTitle(percent:Int)=if(percent in 0..100) "최신 버전으로 업데이트하는 중이에요 $percent%" else UPDATING_WORDS
    /** PackageInstaller's progress (0.0 to 1.0) as a whole percent. */
    fun percentOf(progress:Float)=(progress*100).toInt().coerceIn(0,100)
    const val UPDATING_NOTE="잠시 뒤 이음이 새 버전으로 다시 열려요. 설치 확인 창이 뜨면 설치를 눌러 주세요."

    // ---- after the app was replaced ----
    private const val DONE_TAG="ieum.update.done";private const val DONE_ID=702;private const val CHANNEL="ieum.update"
    const val DONE_TITLE="새 버전으로 업데이트했어요";const val DONE_TEXT="눌러서 열기"
    /** MY_PACKAGE_REPLACED: an install the open app started ended within [RELAUNCH_MS]: the app opens again, and a notification
     * does it when Android does not let the app come to the front by itself. Background installs stay quiet. */
    fun afterReplaced(c:Context,now:Long=System.currentTimeMillis()) {
        val app=c.applicationContext
        if(!relaunchDue(CallUpdate.takeRelaunchMark(app),now)) return
        val open=Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val opened=runCatching { app.startActivity(open) }.isSuccess
        runCatching { notifyDone(app) }
        // "front": Android took the start (it may still not show it; only the phone can tell). "notice": only the notification.
        CallUpdate.noteSetting(app,PipelineSettingsKeys.UPDATE_AUTO_BACK,if(opened) "front" else "notice")
    }
    private fun notifyDone(c:Context) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val m=c.getSystemService(NotificationManager::class.java) ?: return
        m.createNotificationChannel(NotificationChannel(CHANNEL,"이음 업데이트",NotificationManager.IMPORTANCE_DEFAULT))
        val pi=PendingIntent.getActivity(c,3,Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        m.notify(DONE_TAG,DONE_ID,Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
            .setContentTitle(DONE_TITLE).setContentText(DONE_TEXT).setContentIntent(pi).setAutoCancel(true).setLocalOnly(true).build())
    }
    /** The app is open: the "업데이트했어요" notification has done its job. */
    fun clearDone(c:Context) { runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(DONE_TAG,DONE_ID) } }
}
