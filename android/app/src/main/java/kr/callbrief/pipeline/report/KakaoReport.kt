package kr.callbrief.pipeline.report

import android.content.Context
import org.json.JSONObject

/**
 * v3.5 (DESIGN_V5): the morning report over KakaoTalk "나에게 보내기". Key commit: shapes only; r15-1 fills the bodies,
 * r15-3 calls [linkState]/[sendNow] from settings and reads [sentToday] in the morning notice. Signatures stay as they are.
 */
object KakaoReport {
    /** One KakaoTalk message: either a feed (image + two title lines) or a text template (200 chars at most). */
    sealed class Message {
        abstract val link:String
        data class Feed(val png:ByteArray,val title:String,val body:String,override val link:String):Message()
        data class Text(val text:String,override val link:String):Message()
    }
    /** What [compose] makes from a report: the image report and the "오늘 챙길 일" text, plus the text-only fallback. */
    data class Composed(val feed:Message.Feed?,val todayText:Message.Text?,val fallback:List<Message.Text>)
    /** Sending seam (real Kakao SDK in the app, a fake in tests and device-test). Returns null on success, else an error code. */
    interface Sender {
        suspend fun uploadImage(png:ByteArray):String?
        suspend fun send(message:Message,imageUrl:String?):String?
    }
    /** Link state shown in settings. */
    enum class Link { UNAVAILABLE, NOT_LINKED, LINKED, BROKEN }

    @Volatile var senderFactory:((Context)->Sender)?=null

    /** False when the build has no Kakao app key: the feature hides itself in settings. */
    fun available():Boolean = false
    fun linkState(app:Context):Link = Link.UNAVAILABLE
    /** Builds the messages for [report] (the report/YYYY-MM-DD.json object, DESIGN_V5 1절); [phoneFallback] marks the 06:00 phone report. */
    fun compose(report:JSONObject,phoneFallback:Boolean=false):Composed = Composed(null,null,emptyList())
    /** Sends today's report once (REPORT_DAY guards a second send); returns null on success, else an error code. */
    suspend fun send(app:Context,report:JSONObject,source:String):String? = "NOT_IMPLEMENTED"
    /** Settings [지금 보내 보기]: today's report if fetched, else the phone fallback. */
    suspend fun sendNow(app:Context):String? = "NOT_IMPLEMENTED"
    /** True when today's (KST) report already went out over KakaoTalk; the 06:00 app notice stays quiet then. */
    fun sentToday(app:Context,nowMs:Long=System.currentTimeMillis()):Boolean = false
}
