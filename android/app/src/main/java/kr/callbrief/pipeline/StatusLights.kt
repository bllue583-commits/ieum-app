package kr.callbrief.pipeline

/** The five lights of settings "지금 상태" (design B5): 녹음 가져오기, 글자로 바꾸기, 통화 정리, 알림, 업데이트.
 * Each is green ("정상") or amber with the reason and one thing the user can do, in a single sentence. Never red. */
internal object StatusLights {
    data class Light(val tag:String,val label:String,val ok:Boolean,val words:String)
    /** Everything the lights are judged from; all of it is read from settings, permissions and the store already on the phone. */
    data class Input(
        val now:Long,
        val folderConnected:Boolean,
        val modelState:String,
        /** Recordings still to turn into text, and when the last one was done (0: never). */
        val transcribeBacklog:Int=0,val lastTranscribedMs:Long=0,
        val consent:Boolean,
        /** Calls waiting for their summary, the failed ones, how long the oldest has waited, and when the last result came. */
        val waiting:Int=0,val failed:Int=0,val oldestWaitMs:Long?=null,val lastAnalyzedMs:Long=0,
        val notifyAllowed:Boolean=true,
        val updateError:String="",val updateReady:Boolean=false)

    const val TRANSCRIBE_LATE_MS=6*3_600_000L
    const val ANALYZE_LATE_MS=3*3_600_000L
    const val NO_RESULT_MS=12*3_600_000L
    const val OK="정상"

    fun waitWords(ms:Long):String { val m=(ms/60_000).coerceAtLeast(0);return if(m<60) "${m}분" else "${m/60}시간"+(if(m%60>0) " ${m%60}분" else "") }

    fun lights(i:Input):List<Light> {
        fun light(tag:String,label:String,problem:String?)=Light(tag,label,problem==null,problem ?: OK)
        val import=if(!i.folderConnected) "녹음 폴더를 아직 연결하지 않았어요. 아래 연결에서 폴더를 골라 주세요." else null
        val transcribe=when {
            i.modelState=="UNSUPPORTED" -> "이 휴대폰에서는 통화를 글자로 바꿀 수 없어요."
            i.modelState=="FAILED" -> "음성 인식 모델을 받지 못했어요. Wi-Fi에서 아래 모델 받기를 눌러 주세요."
            i.modelState!="READY" -> "음성 인식 모델을 아직 받지 않았어요. Wi-Fi에서 아래 모델 받기를 눌러 주세요."
            i.transcribeBacklog>0 && i.now-i.lastTranscribedMs>TRANSCRIBE_LATE_MS -> "녹음 ${i.transcribeBacklog}개가 밀려 있어요. 충전기에 꽂아 두면 밤에 끝나요."
            else -> null }
        val analyze=when {
            !i.consent -> "정리 동의가 꺼져 있어요. 아래 정리에서 켜 주세요."
            i.failed>0 -> "정리하지 못한 통화가 ${i.failed}건 있어요. 아래 기다리는 통화 다시 정리하기를 눌러 주세요."
            i.waiting>0 && ((i.oldestWaitMs ?: 0)>ANALYZE_LATE_MS || (i.lastAnalyzedMs>0 && i.now-i.lastAnalyzedMs>NO_RESULT_MS)) ->
                (i.oldestWaitMs?.let { "${waitWords(it)} 밀렸어요. " } ?: "정리가 늦어지고 있어요. ")+"순서대로 정리하고 있으니 기다려 주세요."
            else -> null }
        val notify=if(!i.notifyAllowed) "알림이 꺼져 있어요. 휴대폰 설정에서 이음 알림을 켜 주세요." else null
        val update=when {
            // The stored value is a code for the heartbeat; the light shows only its Korean words (a cancel is no error).
            CallUpdate.errorWords(i.updateError)!=null -> CallUpdate.errorWords(i.updateError)!!.sentence
            i.updateReady -> "새 버전이 준비됐어요. 아래 지금 설치를 눌러 주세요."
            else -> null }
        return listOf(light("status_import","녹음 가져오기",import),light("status_transcribe","글자로 바꾸기",transcribe),
            light("status_analyze","통화 정리",analyze),light("status_notify","알림",notify),light("status_update","업데이트",update))
    }
}
