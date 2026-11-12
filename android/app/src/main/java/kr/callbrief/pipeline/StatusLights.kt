package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.pipeline.report.KakaoReport
import org.json.JSONObject

/** The lights of settings "지금 상태" (design B5): 녹음 가져오기, 글자로 바꾸기, 통화 정리, 알림, 업데이트, and (v3.0) 통화 카드.
 * Each is green ("정상") or amber with the reason and one thing the user can do, in a single sentence. Never red. */
internal object StatusLights {
    /** [target]: the tag of the settings group the words point to ("group_연결"), so pressing the line can scroll there; null
     * when the fix is outside the app (the phone's own settings) or there is nothing to do. */
    data class Light(val tag:String,val label:String,val ok:Boolean,val words:String,val target:String?=null)
    /** Tags of the settings groups (CallSettingsActivity.group: "group_" + its name). */
    const val GROUP_CONNECT="group_연결"
    const val GROUP_SUMMARY="group_정리"
    const val GROUP_STATUS="group_상태와 도움"
    const val GROUP_CARD="group_통화 카드"
    /** v3.5 r15-3: the settings group of the morning report (KakaoTalk and the app notice's hour). */
    const val GROUP_MORNING="group_아침 보고"
    /** Everything the lights are judged from; all of it is read from settings, permissions and the store already on the phone. */
    data class Input(
        val now:Long,
        /** A folder was chosen once ([folderConnected]), Android still lets the app read it ([folderReadable]), and when the
         * last discovery cycle ended well (0: never). Review #9: the treeUri text alone said nothing about the permission. */
        val folderConnected:Boolean,
        val modelState:String,
        /** Recordings waiting for their text (QUEUED since 10/1, skipped ones not counted), when the oldest of them entered
         * the queue (review #10, #11: not the call time), and when the last one was done (0: never). */
        val transcribeBacklog:Int=0,val lastTranscribedMs:Long=0,
        val consent:Boolean,
        /** Recent (live) calls without a result for over [LIVE_LATE_MS], the failed ones (an empty transcript is no failure the
         * user can fix), how long the oldest late one has waited, and when the last result came. */
        val waiting:Int=0,val failed:Int=0,val oldestWaitMs:Long?=null,val lastAnalyzedMs:Long=0,
        val notifyAllowed:Boolean=true,
        val updateError:String="",val updateReady:Boolean=false,
        val folderReadable:Boolean=folderConnected,val lastDiscoveryMs:Long=now,
        val oldestQueuedMs:Long?=null,
        /** Since when a downloaded newer version waits to be installed (0: none or unknown). */
        val updateReadySinceMs:Long=0,
        /** v3.0 r11-3 (design 7.2): the call card may be drawn over other apps ([cardOverlay]), the call state may be read
         * ([phoneState], READ_PHONE_STATE: without it no call is seen), the optional call redirection role is held or this
         * phone has none ([cardRole]), and either card switch is on ([cardOn]). */
        val cardOverlay:Boolean=true,val phoneState:Boolean=true,val cardRole:Boolean=true,val cardOn:Boolean=true,
        /** v3.1 (DESIGN_V4 2.1-2): the recording-gap verdict confirmed for the wife ([RecordingGap.confirmed]); null: nothing to say. */
        val gap:RecordingGap.Verdict?=null,
        /** v3.4 (DESIGN_V4 4.2-5, r14-2), filled by [withPhone]: the call log may be read (READ_CALL_LOG); the app's standby bucket
         * ([PhoneStatus.bucketCode]; null: not known); the card counts so far ([kr.callbrief.pipeline.call.CardStats]: calls, no
         * number, no card); scheduled analysis runs missed since the routine last reported ([routineMissed]) and that report's
         * end; the sealed store's share in use; the recording light's green words while texts are still being made. */
        val callLog:Boolean=true,val standbyBucket:String?=null,
        val cardCalls:Int=0,val cardNoNumber:Int=0,val cardNoCard:Int=0,
        val routineMissed:Int=0,val routineEndedMs:Long=0,
        val dbPercent:Int?=null,val pendingText:String?=null,
        /** v3.5 r15-0, filled by [withPhone]: recordings whose text failed (transcription jobs FAILED now), and the recordings
         * have not paired with the calls (the recording-gap verdict noMatch) for more than [NO_MATCH_MS]. */
        val textFailed:Int=0,val noMatchLong:Boolean=false,
        /** v3.5 r15-3 (DESIGN_V5 2.3-3), filled by [withPhone]: the KakaoTalk link ([KakaoReport.linkState]), the settings switch
         * [카카오톡으로 받기] ([PipelineSettingsKeys.MORNING_KAKAO]), the last full send and the failed sends so far. */
        val kakao:KakaoReport.Link=KakaoReport.Link.UNAVAILABLE,
        val kakaoOn:Boolean=true,val kakaoLastSentMs:Long=0,val kakaoFailed:Long=0,
        /** v3.6 (DESIGN_V6 3.2, r16-2), filled by [withPhone]: recordings waiting for the charger and since when the oldest of them
         * waits ([PowerText.waiting], from the power ledger r16-1 writes; null: not known). */
        val chargeWaiting:Int=0,val chargeWaitOldestMs:Long?=null,
        /** v3.6.1 (DESIGN_V7 K4), filled by [withPhone]: today's (KST) calls whose card window was tried ([CardStats] drawn plus
         * fallback plus unconfirmed) and of them the ones whose window was really drawn. */
        val cardTodayTried:Int=0,val cardTodayDrawn:Int=0,
        /** v3.8 r18 (DESIGN_V7 4.1 S4), filled by [withPhone]: when the sealed store last refused a write for its size
         * ([DbStore.lastFullMs]; 0: never). Within [DbStore.FULL_RECENT_MS] the 통화 정리 line says it is full. */
        val dbFullMs:Long=0)

    const val TRANSCRIBE_LATE_MS=6*3_600_000L
    const val ANALYZE_LATE_MS=3*3_600_000L
    /** A live call without its result for this long turns the light amber (design 3.6). */
    const val LIVE_LATE_MS=6*3_600_000L
    const val NO_RESULT_MS=12*3_600_000L
    /** The folder light wants a good discovery cycle within a day, and an update installed within a day of being ready. */
    const val DISCOVERY_LATE_MS=24*3_600_000L
    const val UPDATE_LATE_MS=24*3_600_000L
    const val OK="정상"
    /** v3.5 r15-0: recordings not paired with the calls for this long turn the recording light amber. */
    const val NO_MATCH_MS=48*3_600_000L
    const val NO_MATCH_WORDS="녹음이 2일 넘게 통화와 이어지지 않아요. 관리하는 분께 알려 주세요."
    /** v3.5 r15-3: the KakaoTalk words of the 알림 line (said "위"; [below] turns them while the lights lead the page). */
    const val KAKAO_BROKEN_WORDS="카카오톡 연결이 풀렸어요. 위 아침 보고 묶음에서 다시 연결해 주세요."
    const val KAKAO_FAILING_WORDS="아침 보고가 3일 넘게 카카오톡으로 가지 않았어요. 위 아침 보고 묶음에서 지금 보내 보기를 눌러 주세요."
    /** No full send for this long while the sends kept failing: the 알림 line turns amber. */
    const val KAKAO_LATE_MS=3*24*3_600_000L
    /** Failed sends that count as three days of failure when nothing ever went (a send is tried once a day). */
    const val KAKAO_FAILED_DAYS=3L
    /** The KakaoTalk morning report is linked and on, yet has not gone for over [KAKAO_LATE_MS] while its sends failed (never
     * sent: at least [KAKAO_FAILED_DAYS] failures). */
    fun kakaoFailing(i:Input):Boolean {
        if(i.kakao!=KakaoReport.Link.LINKED || !i.kakaoOn || i.kakaoFailed<=0) return false
        return if(i.kakaoLastSentMs>0) i.now-i.kakaoLastSentMs>KAKAO_LATE_MS else i.kakaoFailed>=KAKAO_FAILED_DAYS
    }
    /** [KakaoReport.linkState] from a settings snapshot already read (no second read on the main thread). */
    fun kakaoLink(s:JSONObject):KakaoReport.Link = when {
        !KakaoReport.available() -> KakaoReport.Link.UNAVAILABLE
        s.optBoolean(PipelineSettingsKeys.KAKAO_LINKED,false) -> KakaoReport.Link.LINKED
        s.optString(PipelineSettingsKeys.KAKAO_LAST_ERROR).startsWith(KakaoReport.TOKEN_PREFIX) -> KakaoReport.Link.BROKEN
        else -> KakaoReport.Link.NOT_LINKED }
    fun textFailedWords(n:Int)="글자로 바꾸지 못한 녹음이 ${n}건 있어요. 새 버전이 오면 한 번 더 바꿔 봐요."

    fun waitWords(ms:Long):String { val m=(ms/60_000).coerceAtLeast(0);return if(m<60) "${m}분" else "${m/60}시간"+(if(m%60>0) " ${m%60}분" else "") }

    fun lights(i:Input):List<Light> {
        fun light(tag:String,label:String,problem:String?,target:String?=null)=Light(tag,label,problem==null,problem ?: OK,if(problem==null) null else target)
        val import=when {
            !i.folderConnected -> "녹음 폴더를 아직 연결하지 않았어요. 위 연결 묶음에서 폴더를 골라 주세요."
            !i.folderReadable -> "녹음 폴더 연결이 풀렸어요. 위 연결 묶음에서 폴더를 다시 골라 주세요."
            i.lastDiscoveryMs<=0 || i.now-i.lastDiscoveryMs>DISCOVERY_LATE_MS ->
                "하루 넘게 녹음을 가져오지 못했어요. 위 연결 묶음에서 폴더를 다시 골라 주세요."
            // v3.1: the folder is read, but the calls of the last day have (almost) no recordings, or the folder is too full.
            else -> i.gap?.let(RecordingGap::lightWords) ?: if(i.noMatchLong) NO_MATCH_WORDS else null }
        val importTarget=if(import!=null && i.gap!=null && i.folderConnected && i.folderReadable && i.lastDiscoveryMs>0 &&
            i.now-i.lastDiscoveryMs<=DISCOVERY_LATE_MS) RecordingGap.lightTarget(i.gap) else GROUP_CONNECT
        val transcribe=when {
            i.modelState=="UNSUPPORTED" -> "이 휴대폰에서는 통화를 글자로 바꿀 수 없어요."
            i.modelState=="FAILED" -> "음성 인식 모델을 받지 못했어요. 아래 [모델 받기]를 눌러 주세요. 어떤 연결에서든 받아요."
            i.modelState!="READY" -> "음성 인식 모델을 아직 받지 않았어요. 아래 [모델 받기]를 눌러 주세요. 어떤 연결에서든 받아요."
            // v3.6: recordings that wait for the charger by design are not "밀려 있어요"; only the others count here.
            i.transcribeBacklog>i.chargeWaiting && (i.oldestQueuedMs?.let { i.now-it>TRANSCRIBE_LATE_MS } ?: true) && i.now-i.lastTranscribedMs>TRANSCRIBE_LATE_MS ->
                "녹음 ${i.transcribeBacklog}개가 밀려 있어요. 충전기에 꽂아 두면 밤에 끝나요."
            // v3.6 (DESIGN_V6 3.2): the oldest recording waiting for the charger has waited over a day and a half.
            chargeLong(i) -> PowerText.lightLong(i.chargeWaiting)
            // v3.5 r15-0: failed texts are told too (each build tries them once more, CallTranscriptionStore.requeueFailedOnce).
            i.textFailed>0 -> textFailedWords(i.textFailed)
            else -> null }
        val analyze=when {
            !i.consent -> "정리 동의가 꺼져 있어요. 위 정리 묶음에서 켜 주세요."
            // v3.4 (4.2-5): the analysis routine missed its scheduled runs (the phone's second eye beside the watchdog).
            i.routineMissed>=ROUTINE_AMBER && i.routineEndedMs>0 ->
                "분석이 ${hoursSince(i.routineEndedMs,i.now)}시간 안 돌았어요. 관리하는 분께 알려 주세요."
            // v3.8 r18 (S4): a write was refused for the store's size; saying it beats the share below.
            DbStore.fullRecent(i.dbFullMs,i.now) -> DbStore.FULL_WORDS
            i.dbPercent!=null && i.dbPercent>=SelfCheckNotices.STORAGE_SHARE_PERCENT ->
                "저장 공간이 거의 찼어요. 관리하는 분께 알려 주세요."
            i.failed>0 -> "정리하지 못한 통화가 ${i.failed}건 있어요. 아래 기다리는 통화 다시 정리하기를 눌러 주세요."
            i.waiting>0 -> (i.oldestWaitMs?.let { "통화 ${i.waiting}건이 ${waitWords(it)} 밀렸어요. " } ?: "정리가 늦어지고 있어요. ")+"순서대로 정리하고 있으니 기다려 주세요."
            else -> null }
        val notify=when {
            !i.notifyAllowed -> "알림이 꺼져 있어요. 휴대폰 설정에서 이음 알림을 켜 주세요."
            // v3.5 r15-3: the morning report over KakaoTalk (the switch off says nothing: the app notice comes as before).
            i.kakao==KakaoReport.Link.BROKEN && i.kakaoOn -> KAKAO_BROKEN_WORDS
            kakaoFailing(i) -> KAKAO_FAILING_WORDS
            else -> null }
        val notifyTarget=if(i.notifyAllowed) GROUP_MORNING else null
        val update=when {
            // The stored value is a code for the heartbeat; the light shows only its Korean words (a cancel is no error).
            CallUpdate.errorWords(i.updateError)!=null -> CallUpdate.errorWords(i.updateError)!!.sentence
            i.updateReady && i.updateReadySinceMs>0 && i.now-i.updateReadySinceMs>UPDATE_LATE_MS -> "새 버전이 하루 넘게 설치되지 않았어요. 아래 지금 설치를 눌러 주세요."
            i.updateReady -> "새 버전이 준비됐어요. 아래 지금 설치를 눌러 주세요."
            else -> null }
        // Where each fix lives: the folder in 연결 (above), the consent in 정리 (above), the model, the retry and the update in
        // 상태와 도움 (below the lights); the notification switch is in the phone's settings, so that line has no target.
        val analyzeTarget=if(!i.consent) GROUP_SUMMARY else if(i.routineMissed>=ROUTINE_AMBER || (i.dbPercent ?: 0)>=SelfCheckNotices.STORAGE_SHARE_PERCENT || DbStore.fullRecent(i.dbFullMs,i.now)) null else GROUP_STATUS
        // The charger wait is fixed outside the app (plug it in): no target.
        val transcribeTarget=if(i.modelState=="UNSUPPORTED" || transcribe==PowerText.lightLong(i.chargeWaiting)) null else GROUP_STATUS
        // v3.4: recordings that are there but still waiting for their text are said in green on the recording line.
        val importLight=if(import==null && i.pendingText!=null) Light("status_import","녹음 가져오기",true,i.pendingText) else light("status_import","녹음 가져오기",import,importTarget)
        // v3.6: recordings waiting for the charger (not yet long) are said in green on the text line.
        val transcribeLight=if(transcribe==null && i.chargeWaiting>0) Light("status_transcribe","글자로 바꾸기",true,PowerText.lightGreen(i.chargeWaiting))
            else light("status_transcribe","글자로 바꾸기",transcribe,transcribeTarget)
        val all=listOf(importLight,transcribeLight,
            light("status_analyze","통화 정리",analyze,analyzeTarget),light("status_notify","알림",notify,notifyTarget),light("status_update","업데이트",update,GROUP_STATUS),
            card(i))
        // v3.5 r15-0: an amber light puts the lights at the top of the page (v3.3), so every group is below them then; without
        // one they sit in 상태와 도움 near the end, below 연결, 정리 and 통화 카드.
        return if(all.any { !it.ok }) all.map { it.copy(words=below(it.words)) } else all
    }
    /** v3.6: recordings wait for the charger and the oldest of them for more than [TranscriptionPower.CHARGING_WAIT_WARN_MS]. */
    fun chargeLong(i:Input)=i.chargeWaiting>0 && i.chargeWaitOldestMs!=null && i.now-i.chargeWaitOldestMs>TranscriptionPower.CHARGING_WAIT_WARN_MS
    /** The settings groups the words name, said as below the lights (they lead the page). */
    fun below(words:String)=BELOW.replace(words) { "${it.groupValues[1]}아래 ${it.groupValues[2]} 묶음" }
    /** v3.7 r17-3: any "위 ○○ 묶음" (a group named later too), not only the four groups of v3.5; "화면 위에" is no group. */
    private val BELOW=Regex("""(^|[ .(])위 ([가-힣]+(?: [가-힣]+)?) 묶음""")

    /** "통화 카드" (v3.0 r11-3): amber without the overlay permission (fixed in the 통화 카드 group) or without the call state
     * (fixed in 연결, where 전화 상태 허용 is). The role for calls that go out is optional: green, and the words say so. */
    private fun card(i:Input):Light {
        val label="통화 카드";val tag="status_card"
        return when {
            !i.cardOn -> Light(tag,label,true,"전화 올 때와 걸 때 모두 꺼 두었어요.")
            !i.cardOverlay -> Light(tag,label,false,"통화 화면 위에 카드를 띄울 수 없어요. 위 통화 카드 묶음에서 카드 보기를 허용해 주세요.",GROUP_CARD)
            // v3.6.1 (K4): the permission read now may say yes while the calls of today did not get their card.
            cardNotDrawnToday(i.cardTodayTried,i.cardTodayDrawn) -> Light(tag,label,false,cardTodayWords(i.cardTodayTried,i.cardTodayDrawn))
            !i.phoneState -> Light(tag,label,false,"전화가 오는 걸 알 수 없어 카드가 뜨지 않아요. 위 연결 묶음에서 전화 상태를 허용해 주세요.",GROUP_CONNECT)
            // v3.4 (4.2-5): the call log tells who called before (thin cards) and whether a call was connected.
            !i.callLog -> Light(tag,label,false,"통화기록을 읽을 수 없어 카드가 덜 채워져요. 위 연결 묶음에서 통화기록 읽기를 허용해 주세요.",GROUP_CONNECT)
            // A phone that may put the app to sleep starts the card late (EXEMPTED: battery use not limited).
            i.standbyBucket!=null && i.standbyBucket !in BUCKETS_FINE -> Light(tag,label,false,
                "휴대폰이 이음을 쉬게 해서 카드가 늦게 뜰 수 있어요. 휴대폰 설정의 애플리케이션, 이음, 배터리에서 제한 없음을 골라 주세요.")
            i.cardCalls>=CARD_MIN_CALLS && i.cardNoNumber*100>=i.cardCalls*NO_NUMBER_PERCENT -> Light(tag,label,false,
                "통화 ${i.cardCalls}건 중 ${i.cardNoNumber}건은 번호를 몰라 카드가 뜨지 않았어요."+
                    if(!i.cardRole) " 위 통화 카드 묶음에서 거는 전화에 번호를 더 빨리 알기를 허용해 주세요." else "",if(!i.cardRole) GROUP_CARD else null)
            i.cardCalls>=CARD_MIN_CALLS && i.cardNoCard*100>=i.cardCalls*NO_CARD_PERCENT -> Light(tag,label,false,
                "통화 ${i.cardCalls}건 중 ${i.cardNoCard}건은 정리된 상대가 아니라 카드가 뜨지 않았어요. 정리된 통화가 없는 번호에도 보려면 위 통화 카드 묶음에서 켜 주세요.",GROUP_CARD)
            !i.cardRole -> Light(tag,label,true,"전화가 오면 카드가 떠요. 거는 전화에도 보려면 위 통화 카드 묶음에서 허용해 주세요.",GROUP_CARD)
            else -> Light(tag,label,true,OK)
        }
    }

    /** v3.6.1 (K4): amber from this many tried calls of today, when more than half of them were not drawn. */
    const val CARD_TODAY_MIN=3
    fun cardNotDrawnToday(tried:Int,drawn:Int)=tried>=CARD_TODAY_MIN && (tried-drawn.coerceIn(0,tried))*2>tried
    fun cardTodayWords(tried:Int,drawn:Int)="통화 카드가 오늘 안 뜨고 있어요. 오늘 통화 ${tried}건 중 ${tried-drawn.coerceIn(0,tried)}건은 카드 대신 알림으로 보여 드렸어요."
    /** Today's tried and drawn calls from the stored card counts [stats] (zeros when they are of another KST day). */
    fun cardToday(stats:JSONObject?,now:Long):Pair<Int,Int> {
        val st=stats ?: return 0 to 0
        if(st.optInt("day",0)!=kr.callbrief.pipeline.call.CardStats.day(now)) return 0 to 0
        val t=st.optJSONObject("today") ?: return 0 to 0
        val drawn=t.optInt(kr.callbrief.pipeline.call.CardStats.DRAWN,0)
        // v3.6.1 fix (review, critical): a window seen although the check said no is unconfirmed: tried, not drawn.
        return drawn+t.optInt(kr.callbrief.pipeline.call.CardStats.FALLBACK,0)+t.optInt(kr.callbrief.pipeline.call.CardStats.UNCONFIRMED,0) to drawn
    }

    // ---- v3.4 (DESIGN_V4 4.2-5, r14-2) ------------------------------------------------------------------------------
    /** Standby buckets that do not hold the card back (unknown and older phones count as fine). */
    val BUCKETS_FINE=setOf("EXEMPTED","NONE","UNKNOWN")
    /** The card line wants this many calls before it judges shares, then amber at these shares (%). */
    const val CARD_MIN_CALLS=5
    const val NO_NUMBER_PERCENT=20
    const val NO_CARD_PERCENT=60
    /** Scheduled runs missed: amber on the 통화 정리 line from [ROUTINE_AMBER], the evening self-check notice from [ROUTINE_NOTICE]. */
    const val ROUTINE_AMBER=2
    const val ROUTINE_NOTICE=4
    /** A run is missed once this long has passed after its slot without a later report (the run takes a few minutes). */
    const val ROUTINE_GRACE_MS=40*60_000L
    fun hoursSince(ms:Long,now:Long):Long=((now-ms)/3_600_000L).coerceAtLeast(1)
    /** The routine's scheduled runs ([crons], KST) after its last report ended at [endedMs] whose slot passed more than
     * [ROUTINE_GRACE_MS] before [now]. 0 when the routine never reported. The night counts nothing: before the first morning run
     * the last one was the evening's, which ended. */
    fun routineMissed(endedMs:Long,crons:List<ResultChain.Cron>,now:Long):Int {
        if(endedMs<=0 || now-ROUTINE_GRACE_MS<=endedMs) return 0
        return ResultChain.runs(crons,endedMs+1,now-ROUTINE_GRACE_MS).size
    }
    /** [routineMissed] from the settings [s]: not counted while the phone itself cannot reach the inbox (its own error is newer
     * than the routine's report: the "연결이 끊겼어요" notice speaks then). */
    fun routineMissed(s:JSONObject,now:Long):Int {
        val ended=s.optLong(PipelineSettingsKeys.ROUTINE_ENDED_MS,0)
        if(s.optLong(PipelineSettingsKeys.LAST_INBOX_ERROR_MS,0)>ended) return 0
        return routineMissed(ended,ResultChain.crons(s),now)
    }
    /** [i] with what v3.4 reads from the phone and the settings [s]: the call log permission, the standby bucket, the card
     * counts, the routine's missed runs, the store's share ([dbPercent], from the store read the page already did) and the
     * recording light's green words. v3.5 r15-4 (H1): no store is opened here (it runs on the main thread); [Input.textFailed]
     * comes in from [Store.textFailed], read on the IO thread. */
    fun withPhone(i:Input,app:Context,s:JSONObject,dbPercent:Int?):Input {
        val stats=runCatching { kr.callbrief.pipeline.call.CardStats.read(s) }.getOrNull()
        // While this page is open the app is ACTIVE whatever its standing: battery use not limited is what makes it EXEMPTED.
        val free=runCatching { app.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName)==true }.getOrDefault(false)
        val bucket=if(free) "EXEMPTED" else if(android.os.Build.VERSION.SDK_INT<28) null else runCatching {
            PhoneStatus.bucketCode(app.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket) }.getOrNull()
        return i.copy(callLog=app.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)==android.content.pm.PackageManager.PERMISSION_GRANTED,
            standbyBucket=bucket,
            cardCalls=stats?.optInt(kr.callbrief.pipeline.call.CardStats.CALLS,0) ?: 0,
            cardNoNumber=stats?.optInt(kr.callbrief.pipeline.call.CardStats.NO_NUMBER,0) ?: 0,
            cardNoCard=stats?.optInt(kr.callbrief.pipeline.call.CardStats.NO_CARD,0) ?: 0,
            routineMissed=runCatching { routineMissed(s,i.now) }.getOrDefault(0),routineEndedMs=s.optLong(PipelineSettingsKeys.ROUTINE_ENDED_MS,0),
            dbPercent=dbPercent,pendingText=RecordingGap.pendingText(s,i.now),dbFullMs=runCatching { DbStore.lastFullMs(s) }.getOrDefault(0L),
            noMatchLong=noMatchLong(s,i.now),
            kakao=runCatching { kakaoLink(s) }.getOrDefault(KakaoReport.Link.UNAVAILABLE),
            kakaoOn=s.optBoolean(PipelineSettingsKeys.MORNING_KAKAO,true),kakaoLastSentMs=s.optLong(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,0),
            kakaoFailed=s.optLong(PipelineSettingsKeys.KAKAO_FAILED_COUNT,0)).let { j ->
                val (tried,drawn)=runCatching { cardToday(stats,i.now) }.getOrDefault(0 to 0)
                j.copy(cardTodayTried=tried,cardTodayDrawn=drawn) }.let { j ->
                val w=runCatching { PowerText.waiting(PowerText.ledger(s)) }.getOrNull() ?: return@let j
                j.copy(chargeWaiting=w.n,chargeWaitOldestMs=w.oldestMs) }
    }
    /** v3.5 r15-0: the last recording-gap verdict is noMatch, fresh, and has held for more than [NO_MATCH_MS]. */
    fun noMatchLong(s:JSONObject,now:Long):Boolean {
        val v=RecordingGap.last(s)?.takeIf { it.code==PipelineSettingsKeys.GAP_NO_MATCH } ?: return false
        return now-v.atMs<=RecordingGap.STALE_MS && v.sinceMs>0 && now-v.sinceMs>NO_MATCH_MS
    }

    // ---- what the lights, the heartbeat and the self-check read from the store (one place, so they agree) ----

    /** Transcription queue split by the first pass: [newCount] recent recordings, [oldCount] first-pass ones; each with the
     * time its oldest row entered the queue (jobs.updated_ms), not the call time. Recordings before 10/1 are not counted. */
    data class Queue(val newCount:Int=0,val newOldestMs:Long?=null,val oldCount:Int=0,val oldOldestMs:Long?=null,
                     /** v3.1 (DESIGN_V4 2.3-4): when the oldest queued job entered the queue by its own queued_ms
                      * ([CallTranscriptionStore.oldestQueuedMs]); null: not known, the rows' times above are used. */
                     val queuedSinceMs:Long?=null) {
        val count get()=newCount+oldCount
        val oldestMs get()=queuedSinceMs ?: listOfNotNull(newOldestMs,oldOldestMs).minOrNull()
    }
    /** [q] with the queue's own entry time when the transcription store knows it ([oldest], r12-3), else as it is. */
    fun withQueuedSince(q:Queue,oldest:Long?):Queue = if(oldest==null || q.count==0) q else q.copy(queuedSinceMs=oldest)
    /** One QUEUED row: the recording's call time (null: unknown) and when the row entered the queue. */
    data class QueuedRow(val callMs:Long?,val enteredMs:Long)
    fun queue(rows:List<QueuedRow>,s:JSONObject,now:Long=System.currentTimeMillis()):Queue {
        val kept=rows.filterNot { FirstPass.beforeRange(it.callMs) }
        val (old,new)=kept.partition { r -> r.callMs?.takeIf { it>0 }?.let { FirstPass.isOld(it,s,now) } ?: false }
        return Queue(new.size,new.minOfOrNull { it.enteredMs },old.size,old.minOfOrNull { it.enteredMs })
    }
    /** One call record waiting for its result: when it was stored and the call's time. */
    data class WaitingRow(val createdMs:Long,val dayMs:Long)
    /** Live (not first-pass) records without a result for over [LIVE_LATE_MS], and how long the oldest of them waited. */
    fun lateLive(rows:List<WaitingRow>,s:JSONObject,now:Long):Pair<Int,Long?> {
        val late=rows.filter { !FirstPass.isOld(it.dayMs,s,now) && now-it.createdMs>LIVE_LATE_MS }
        return late.size to late.minOfOrNull { it.createdMs }?.let { now-it }
    }
    /** The protected store's share in use (design B1, review data #4): the sealed file against its 16 MB limit and the rows
     * against the 100,000 row limit of a snapshot, whichever is fuller. */
    const val ROW_LIMIT=100_000L
    fun dbPercent(bytes:Long?,rows:Long?):Int? {
        if(bytes==null && rows==null) return null
        val b=bytes?.let { it*100/CallRecordStore.DB_LIMIT_BYTES } ?: 0
        val r=rows?.let { it*100/ROW_LIMIT } ?: 0
        return maxOf(b,r).toInt().coerceIn(0,100)
    }

    /** Everything above, read in one go from the store. Counts and times only. Each part is null when it could not be read. */
    data class Store(val queue:Queue?,val lateLive:Int?,val oldestLateMs:Long?,val failed:Int?,val dbBytes:Long?,val dbRows:Long?,
                     /** v3.5 r15-4 (H1): transcription jobs FAILED now, read here on the IO thread (null: not read). */
                     val textFailed:Int?=null) {
        val dbPercent:Int? get()=dbPercent(dbBytes,dbRows)
    }
    fun dbFile(app:Context)=kr.co.callsummary.core.android.ProtectedDatabase.file(app,kr.callbrief.GeneralLocalStore.DATABASE)
    fun read(app:Context,s:JSONObject,now:Long):Store {
        val bytes=runCatching { dbFile(app).takeIf { it.isFile }?.length() }.getOrNull()
        var queue:Queue?=null;var late:Int?=null;var oldestLate:Long?=null;var failed:Int?=null;var rowsTotal:Long?=null
        var textFailed:Int?=null
        runCatching {
            kr.co.callsummary.core.android.AndroidSqlPort(app,kr.callbrief.GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p ->
                fun n(v:Any?)=(v as? Number)?.toLong()
                val tables=p.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").map { it["name"].toString() }.toSet()
                runCatching { rowsTotal=tables.sumOf { t -> n(p.query("SELECT count(*) AS n FROM \"${t.replace("\"","\"\"")}\"").single()["n"]) ?: 0L } }
                if("call_transcription_jobs" in tables) runCatching {
                    val call=if("local_files" in tables) "coalesce(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms)" else "coalesce(r.original_started_at_ms,r.modified_at_ms)"
                    val join=if("local_files" in tables) "LEFT JOIN local_files l ON l.recording_id=j.recording_id" else ""
                    queue=queue(p.query("SELECT j.updated_ms AS entered,$call AS call_ms FROM call_transcription_jobs j LEFT JOIN recordings r ON r.recording_id=j.recording_id $join WHERE j.state='QUEUED'")
                        .map { QueuedRow(n(it["call_ms"]),n(it["entered"]) ?: now) },s)
                } else queue=Queue()
                // v3.5 r15-4 (H1): the failed texts ([CallTranscriptionStore.failedCount]'s count) in the same read, so the lights
                // never open a store on the main thread and this read changes nothing in the file.
                textFailed=if("call_transcription_jobs" in tables) runCatching {
                    (n(p.query("SELECT count(*) AS n FROM call_transcription_jobs WHERE state='FAILED'").single()["n"]) ?: 0L).toInt() }.getOrNull() else 0
                if("call_records" in tables) runCatching {
                    val (c,o)=lateLive(p.query("SELECT created_ms,coalesce(started_ms,created_ms) AS day_ms FROM call_records WHERE state IN ('TRANSCRIBED','WAITING_CONSENT')")
                        .map { WaitingRow(n(it["created_ms"]) ?: now,n(it["day_ms"]) ?: now) },s,now)
                    late=c;oldestLate=o
                    failed=(n(p.query("SELECT count(*) AS n FROM call_records WHERE state='FAILED' AND coalesce(error,'')!='EMPTY_TRANSCRIPT'").single()["n"]) ?: 0L).toInt()
                } else { late=0;failed=0 }
            }
        }.onFailure { android.util.Log.w("ieum.status","store not read: ${it.javaClass.simpleName}") }
        // v3.1: the age of the wait by the queue's own entry time (r12-3); null keeps the rows' times.
        queue=queue?.let { q -> if(q.count==0) q else withQueuedSince(q,runCatching { CallTranscriptionStore(app).use { it.oldestQueuedMs() } }.getOrNull()) }
        return Store(queue,late,oldestLate,failed,bytes,rowsTotal,textFailed)
    }
    /** Android still lets the app read the chosen folder (the heartbeat's "folder" and the folder light). */
    fun folderReadable(app:Context,s:JSONObject):Boolean {
        val tree=s.optString("treeUri")
        return tree.isNotBlank() && runCatching { app.contentResolver.persistedUriPermissions.any { it.uri.toString()==tree && it.isReadPermission } }.getOrDefault(false)
    }
}
