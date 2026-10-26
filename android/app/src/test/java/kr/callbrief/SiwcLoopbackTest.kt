package kr.callbrief

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only 127.0.0.1 fixture callbacks; no OpenAI, browser, credentials or actual grant. */
class SiwcLoopbackTest {
    private fun send(url:String,host:String?=null):String {val u=URI(url);return Socket("127.0.0.1",u.port).use{s->s.soTimeout=3000
        s.getOutputStream().write(("GET ${u.rawPath}?${u.rawQuery} HTTP/1.1\r\nHost: ${host ?: "127.0.0.1:${u.port}"}\r\n\r\n").toByteArray());s.getOutputStream().flush();s.getInputStream().bufferedReader().readText()}}
    @Test fun listenerBindsBeforeAuthorizationAndRejectsWrongStateThenAcceptsOneCode() {
        val f=SiwcAuthFixture();val executor=Executors.newSingleThreadExecutor()
        try {SiwcLoopback().use{l->val p=SiwcOAuthProtocol.Pending(SiwcOAuthConfig("PUBLIC_FIXTURE","fixture-client"),"urn:uuid:public-host",l.redirect,true,null,1,f.now)
            val wait=executor.submit<SiwcOAuthProtocol.Code>{l.await(p,{false},{f.now})}
            assertTrue(send(f.callback(p).replace(p.state,"wrong")).startsWith("HTTP/1.1 400"))
            val response=send(f.callback(p));assertTrue(response.startsWith("HTTP/1.1 200"));assertFalse(response.contains("PUBLIC_CODE"));assertEquals("PUBLIC_CODE",wait.get(3,TimeUnit.SECONDS).value)}}finally{executor.shutdownNow()}
    }
    @Test fun hostMismatchCannotConsumeCorrectCallback() {
        val f=SiwcAuthFixture();val executor=Executors.newSingleThreadExecutor()
        try {SiwcLoopback().use{l->val p=SiwcOAuthProtocol.Pending(SiwcOAuthConfig("PUBLIC_FIXTURE","fixture-client"),"urn:uuid:public-host",l.redirect,true,null,1,f.now)
            val wait=executor.submit<SiwcOAuthProtocol.Code>{l.await(p,{false},{f.now})}
            assertTrue(send(f.callback(p),"example.invalid").startsWith("HTTP/1.1 400"));send(f.callback(p));assertEquals("fixture-client",wait.get(3,TimeUnit.SECONDS).clientId)}}finally{executor.shutdownNow()}
    }
    @Test fun cancellationAndExpiryStopWithoutOpeningBrowserOrExchangingCode() {val f=SiwcAuthFixture();SiwcLoopback().use{l->val p=SiwcOAuthProtocol.Pending(SiwcOAuthConfig("PUBLIC_FIXTURE","fixture-client"),"urn:uuid:public-host",l.redirect,true,null,1,f.now)
        assertNotNull(runCatching{l.await(p,{true},{f.now})}.exceptionOrNull());assertNotNull(runCatching{l.await(p,{false},{f.now+300001})}.exceptionOrNull())}}
}
