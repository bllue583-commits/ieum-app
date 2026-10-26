package kr.callbrief

/** One screen operation owns one cancellation target. A stop before its pilot is created
 * is remembered, so late attachment cannot start work after the user stopped/closed it. */
internal class PublicAudioOperation {
    private val monitor=Any()
    private var target:(()->Unit)?=null
    @Volatile var cancelled=false;private set
    fun attach(cancel:()->Unit) {
        val stopNow=synchronized(monitor){check(target==null){"PUBLIC_AUDIO_TARGET_ALREADY_ATTACHED"};target=cancel;cancelled}
        if(stopNow)cancel()
    }
    fun cancel() {
        val action=synchronized(monitor){if(cancelled)return;cancelled=true;target}
        action?.invoke()
    }
    fun requireRunning(){check(!cancelled){"ANDROID_STT_STOPPED"}}
}
