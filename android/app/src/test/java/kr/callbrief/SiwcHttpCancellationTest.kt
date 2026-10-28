package kr.callbrief

import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** In-process interceptors only: no DNS, TLS, OAuth grant or OpenAI request. */
class SiwcHttpCancellationTest {
    private val request=SiwcAstraProtocolTest().request()
    private fun grant()=SiwcRuntimeGrant(request.scope,"PUBLIC_FIXTURE_NOT_A_REAL_TOKEN_12345678",Long.MAX_VALUE,true,setOf(request.key()))
    private fun waitForCancel(call:Call,entered:CountDownLatch):Nothing {
        entered.countDown()
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(!call.isCanceled() && System.nanoTime()<deadline)Thread.sleep(5)
        check(call.isCanceled()) { "FIXTURE_CANCEL_TIMEOUT" }
        throw IOException("fixture cancelled")
    }
    private fun blocked(body:Boolean,catalog:Boolean=false,stop:(AtomicBoolean,AtomicReference<SiwcRuntimeGrant?>)->Unit) {
        val entered=CountDownLatch(1);val calls=AtomicInteger();val seen=AtomicReference<Call>()
        val active=AtomicReference<SiwcRuntimeGrant?>(grant());val stopped=AtomicBoolean(false)
        val client=OkHttpClient.Builder().addInterceptor { chain->
            calls.incrementAndGet();seen.set(chain.call())
            if(!body)waitForCancel(chain.call(),entered)
            val stream=object:InputStream(){override fun read():Int=waitForCancel(chain.call(),entered)}
            val responseBody=object:ResponseBody(){
                override fun contentType():MediaType="text/event-stream".toMediaType()
                override fun contentLength()=-1L
                override fun source():BufferedSource=stream.source().buffer()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").header("Content-Type","text/event-stream").body(responseBody).build()
        }.build()
        val executor=Executors.newSingleThreadExecutor()
        try {
            val http=SiwcAstraHttp({active.get()},calls=client)
            val result=executor.submit<Throwable?> {runCatching {if(catalog)http.models(request.scope) else http.invoke(request){stopped.get()}}.exceptionOrNull()}
            assertTrue("HTTP fixture entered",entered.await(2,TimeUnit.SECONDS))
            stop(stopped,active)
            assertNotNull("no completion after cancellation",result.get(3,TimeUnit.SECONDS))
            assertTrue(seen.get().isCanceled());assertEquals("no automatic retry",1,calls.get())
        } finally {seen.get()?.cancel();executor.shutdownNow()}
    }
    @Test fun stopInterruptsBlockedResponseHeaders()=blocked(false){stop,_->stop.set(true)}
    @Test fun stopInterruptsBlockedEventStream()=blocked(true){stop,_->stop.set(true)}
    @Test fun logoutInterruptsBlockedEventStream()=blocked(true){_,active->active.set(null)}
    @Test fun replacementAccountInterruptsCatalogHeaders()=blocked(false,true){_,active->active.set(grant())}
    @Test fun invalidatedGrantInterruptsCatalogBody()=blocked(true,true){_,active->active.set(null)}
    @Test fun finishedCallReleasesWatchWithoutLateCancellation() {
        val seen=AtomicReference<Call>();val active=AtomicReference<SiwcRuntimeGrant?>(grant());val calls=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            calls.incrementAndGet();seen.set(chain.call())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").header("Content-Type","text/event-stream")
                .body(SiwcAstraProtocolTest().event(effort="xhigh").toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val response=SiwcAstraHttp({active.get()},calls=client).invoke(request){false}
        assertEquals("resp_public_fixture",response.responseId)
        active.set(null);Thread.sleep(250)
        assertFalse("completed request monitor removed",seen.get().isCanceled());assertEquals(1,calls.get())
    }
}
