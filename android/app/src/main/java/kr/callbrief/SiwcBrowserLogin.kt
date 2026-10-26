package kr.callbrief

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal interface SiwcBrowserSurface {
    fun open(url:String)
    /** A request to return is not proof Android actually foregrounded the activity. */
    fun requestReturn():Boolean
}
internal class SiwcAndroidBrowser(context:Context,returnActivity:Class<out Activity>):SiwcBrowserSurface {
    private val app=context.applicationContext
    private val returnIntent:PendingIntent
    init {require(returnActivity.name.startsWith(app.packageName+"."))
        returnIntent=PendingIntent.getActivity(app,7811,Intent(app,returnActivity).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
    override fun open(url:String) {
        val uri=url.toUri();check(uri.scheme=="https" && uri.host=="auth.openai.com" && uri.port in setOf(-1,443) && uri.userInfo==null && uri.fragment==null && uri.path=="/api/accounts/authorize") { "SIWC_BROWSER_ORIGIN" }
        app.startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun requestReturn()=runCatching{returnIntent.send();true}.getOrDefault(false)
}

/** Bounded one-shot HTTP loopback. It receives only a code/state, never audio or account tokens.
 * No externally reachable bind, WebView, custom-scheme credential callback, or persistent listener. */
internal class SiwcLoopback:AutoCloseable {
    private val server=ServerSocket().apply{reuseAddress=false;bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4);soTimeout=500}
    val redirect="http://127.0.0.1:${server.localPort}/auth/callback"
    private fun request(socket:Socket):String {
        check(socket.inetAddress.isLoopbackAddress) { "SIWC_CALLBACK_PEER" };socket.soTimeout=1000
        val out=java.io.ByteArrayOutputStream();val input=socket.getInputStream();var tail=0
        while(true) {val b=input.read();check(b>=0 && out.size()<12288) { "SIWC_CALLBACK_HTTP_SIZE" };out.write(b);tail=(tail shl 8) or b;if(tail==0x0d0a0d0a)break}
        val lines=out.toString("US-ASCII").split("\r\n");val first=lines.first().split(' ')
        check(first.size==3 && first[0]=="GET" && first[2]=="HTTP/1.1" && first[1].startsWith("/auth/callback?")) { "SIWC_CALLBACK_HTTP" }
        val headers=mutableMapOf<String,String>();for(line in lines.drop(1).filter{it.isNotEmpty()}) {
            val p=line.indexOf(':');check(p>0 && !line.startsWith(' ') && !line.startsWith('\t')) { "SIWC_CALLBACK_HTTP" }
            val k=line.substring(0,p).lowercase();check(k !in headers) { "SIWC_CALLBACK_HTTP" };headers[k]=line.substring(p+1).trim()
        }
        check(headers["host"]=="127.0.0.1:${server.localPort}" && headers["transfer-encoding"]==null && (headers["content-length"]==null || headers["content-length"]=="0")) { "SIWC_CALLBACK_HTTP" }
        return "http://127.0.0.1:${server.localPort}"+first[1]
    }
    private fun respond(socket:Socket,ok:Boolean) {
        // No reflected URL/code/error, links, scripts or automatic custom-scheme redirects.
        val body=(if(ok)"Authorization response received. Return to Ieum." else "Invalid authorization response.").toByteArray(Charsets.UTF_8)
        val status=if(ok)"200 OK" else "400 Bad Request"
        socket.getOutputStream().apply{write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII));write(body);flush()}
    }
    fun await(pending:SiwcOAuthProtocol.Pending,stopped:()->Boolean,now:()->Long=System::currentTimeMillis):SiwcOAuthProtocol.Code {
        var invalid=0
        while(!stopped() && now()>=pending.createdMs && now()-pending.createdMs<300000) {
            val socket=try{server.accept()}catch(_:SocketTimeoutException){continue}
            socket.use {
                try {val code=pending.callback(request(it),now());runCatching{respond(it,true)};return code}
                catch(e:SiwcOAuthProtocol.Failure) {runCatching{respond(it,true)};throw e}
                catch(_:Exception) {runCatching{respond(it,false)};if(++invalid>=32)error("SIWC_CALLBACK_REJECTED")}
            }
        }
        error(if(stopped())"SIWC_LOGIN_CANCELLED" else "SIWC_LOGIN_EXPIRED")
    }
    override fun close(){server.close()}
}

/** Retain in the app's login lifecycle owner across browser switching/rotation. Main UI callbacks
 * carry no credentials. Closing cancels pending acceptance and the listener; no OS service is added.
 * Android background activity restrictions/OEM process death still require real-device QA. */
internal class SiwcBrowserLogin(private val sessions:SiwcSessionStore,private val transport:SiwcAuthTransport,
    private val browser:SiwcBrowserSurface,private val main:Executor,private val now:()->Long=System::currentTimeMillis):AutoCloseable {
    class Outcome(val status:String,val registrationKey:String?=null,val returnRequested:Boolean=false)
    private val executor=Executors.newSingleThreadExecutor {r->Thread(r,"ieum-siwc-login").apply{isDaemon=true}}
    private val closed=AtomicBoolean(false)
    @Volatile private var active:Handle?=null
    inner class Handle:AutoCloseable {
        val cancelled=AtomicBoolean(false)
        @Volatile internal var listener:SiwcLoopback?=null
        @Volatile internal var pending:SiwcOAuthProtocol.Pending?=null
        override fun close(){cancelled.set(true);runCatching{listener?.close()};pending?.let{runCatching{sessions.cancel(it)}}}
    }
    @Synchronized fun start(config:SiwcOAuthConfig?,selectedKey:String?,requestPlan:Boolean,onOutcome:(Outcome)->Unit):Handle {
        check(!closed.get()) { "SIWC_LOGIN_CLOSED" };active?.close();val handle=Handle();active=handle
        if(config==null) {main.execute{onOutcome(Outcome("SIWC_AUTH_NOT_CONFIGURED"))};return handle}
        executor.execute {
            var registration:String?=null;var status="SIWC_LOGIN_FAILED"
            try {
                check(!handle.cancelled.get()) { "SIWC_LOGIN_CANCELLED" }
                val metadata=transport.metadata()
                SiwcLoopback().use{listener->handle.listener=listener
                    val p=sessions.begin(config,listener.redirect,selectedKey,requestPlan);handle.pending=p
                    val opened=java.util.concurrent.CompletableFuture<Unit>()
                    main.execute {try{check(!handle.cancelled.get() && !closed.get()) { "SIWC_LOGIN_CANCELLED" };browser.open(p.authorizeUrl());opened.complete(Unit)}catch(e:Exception){opened.completeExceptionally(e)}}
                    opened.get(30,java.util.concurrent.TimeUnit.SECONDS)
                    val code=listener.await(p,{handle.cancelled.get() || closed.get() || !sessions.pendingCurrent(p)},now)
                    check(!handle.cancelled.get() && !closed.get()) { "SIWC_LOGIN_CANCELLED" }
                    registration=sessions.finish(p,code,metadata).key;status="SIWC_IDENTITY_VERIFIED"
                }
            } catch(e:Exception) {
                status=if(handle.cancelled.get() || closed.get())"SIWC_LOGIN_CANCELLED" else (e as? SiwcOAuthProtocol.Failure)?.code
                    ?: e.message?.takeIf{it.matches(Regex("SIWC_[A-Z0-9_]{1,100}"))} ?: "SIWC_LOGIN_FAILED"
            } finally {handle.pending?.let{runCatching{sessions.cancel(it)}};handle.listener=null}
            val result=status;val key=registration
            main.execute {if(active===handle && !closed.get())onOutcome(Outcome(result,key,runCatching{browser.requestReturn()}.getOrDefault(false)))}
        };return handle
    }
    override fun close(){closed.set(true);active?.close();executor.shutdownNow()}
}
