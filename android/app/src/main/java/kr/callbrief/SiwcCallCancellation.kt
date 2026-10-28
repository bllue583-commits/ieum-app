package kr.callbrief

import okhttp3.Call
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Cancels blocked connect/header/body reads when local approval ceases to be current.
 * Closing the local call does not prove the server stopped or avoided usage. No retry is made.
 * One shared daemon serves only active requests; cancelled tasks are removed from its queue. */
internal class SiwcCallCancellation(private val call:Call,private val stopped:()->Boolean):AutoCloseable {
    private val task=timer.scheduleWithFixedDelay({
        if(runCatching(stopped).getOrDefault(true))call.cancel()
    },0,100,TimeUnit.MILLISECONDS)
    override fun close(){task.cancel(false)}
    companion object {
        private val timer=ScheduledThreadPoolExecutor(1){r->Thread(r,"ieum-siwc-call-cancel").apply{isDaemon=true}}.apply {
            removeOnCancelPolicy=true
            setKeepAliveTime(1,TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }
}
