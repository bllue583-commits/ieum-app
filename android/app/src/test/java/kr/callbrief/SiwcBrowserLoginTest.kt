package kr.callbrief

import android.app.Application
import android.content.Intent
import kr.callagent.drive.StrictJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SiwcBrowserLoginTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication() as Application
    private val f=SiwcAuthFixture()
    private inner class Port:SiwcAuthTransport {
        var calls=0;var discoveries=0;var authorization=""
        override fun metadata()=f.metadata.also{discoveries++}
        override fun jwks(metadata:SiwcOAuthProtocol.Metadata)=f.jwks
        override fun token(form:Map<String,String>):ByteArray {
            calls++;val url=authorization.toHttpUrl();assertEquals(url.queryParameter("redirect_uri"),form["redirect_uri"]);assertEquals("fixture-issued",form["client_id"])
            val p=f.pending("fixture-issued")
            return f.tokens(p,change={it["id_token"]=f.jwt(p,change={c->c["nonce"]=url.queryParameter("nonce")})})
        }
        override fun revoke(metadata:SiwcOAuthProtocol.Metadata,clientId:String,refreshToken:String)=true
    }
    @Test fun unsetConfigDoesNotOpenBrowserDiscoverOrBind()=runBlocking(Dispatchers.IO) {
        val port=Port();val browser=object:SiwcBrowserSurface {override fun open(url:String){error("MUST_NOT_OPEN")};override fun requestReturn()=false}
        SiwcSessionStore(app,port,{f.now}).use{s->SiwcBrowserLogin(s,port,browser,Executor{it.run()},{f.now}).use{login->
            var outcome="";login.start(null,null,true){outcome=it.status};assertEquals("SIWC_AUTH_NOT_CONFIGURED",outcome);assertEquals(0,port.discoveries);assertEquals(0,port.calls)}}
    }
    @Test fun syntheticBrowserCallbackExchangesAndValidatesThenRequestsReturn()=runBlocking(Dispatchers.IO) {
        val port=Port();val done=CountDownLatch(1);val result=AtomicReference<SiwcBrowserLogin.Outcome>();var returned=0
        val browser=object:SiwcBrowserSurface {
            override fun open(url:String) {port.authorization=url;val u=url.toHttpUrl();assertEquals("dynamic_agent_client",u.queryParameter("client_id"))
                Thread {val callback=URI(u.queryParameter("redirect_uri"));Socket("127.0.0.1",callback.port).use{s->s.soTimeout=5000
                    val target=callback.path+"?state="+u.queryParameter("state")+"&code=PUBLIC_BROWSER_CODE&client_id=fixture-issued"
                    s.getOutputStream().write(("GET $target HTTP/1.1\r\nHost: 127.0.0.1:${callback.port}\r\n\r\n").toByteArray());s.getOutputStream().flush();s.getInputStream().bufferedReader().readText()}}.start()
            }
            override fun requestReturn():Boolean {returned++;return true}
        }
        SiwcSessionStore(app,port,{f.now}).use{s->SiwcBrowserLogin(s,port,browser,Executor{it.run()},{f.now}).use{login->
            login.start(SiwcOAuthConfig("PUBLIC_LOCAL_FIXTURE",null,true),null,true){result.set(it);done.countDown()}
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals("SIWC_IDENTITY_VERIFIED",result.get().status);assertTrue(result.get().returnRequested)
            assertEquals(s.activeKey(),result.get().registrationKey);assertEquals(1,port.calls);assertEquals(1,returned)}}
    }
    @Test fun closingLoopbackCancelsPendingAttemptWithoutTokenExchange()=runBlocking(Dispatchers.IO) {
        val port=Port();val opened=CountDownLatch(1);val done=CountDownLatch(1);val result=AtomicReference<SiwcBrowserLogin.Outcome>()
        val browser=object:SiwcBrowserSurface {override fun open(url:String){opened.countDown()};override fun requestReturn()=true}
        SiwcSessionStore(app,port,{f.now}).use{s->SiwcBrowserLogin(s,port,browser,Executor{it.run()},{f.now}).use{login->
            val handle=login.start(SiwcOAuthConfig("PUBLIC_LOCAL_FIXTURE",null,true),null,true){result.set(it);done.countDown()}
            assertTrue(opened.await(5,TimeUnit.SECONDS));handle.close();assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals("SIWC_LOGIN_CANCELLED",result.get().status);assertEquals(0,port.calls);assertTrue(s.activeKey().isBlank())}}
    }
    @Test fun androidSurfaceUsesExternalViewAndExplicitTokenFreeReturnIntent()=runBlocking(Dispatchers.IO) {
        val browser=SiwcAndroidBrowser(app,MainActivity::class.java);val url=f.pending().authorizeUrl();browser.open(url)
        val intent=shadowOf(app).nextStartedActivity;assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals(url,intent.dataString);assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(browser.requestReturn());val returned=shadowOf(app).nextStartedActivity;assertEquals(MainActivity::class.java.name,returned.component!!.className);assertNull(returned.data);assertTrue(returned.extras==null || returned.extras!!.isEmpty)
    }
}
