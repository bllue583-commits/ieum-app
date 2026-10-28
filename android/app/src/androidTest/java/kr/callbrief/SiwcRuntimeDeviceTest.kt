package kr.callbrief

import android.os.Process
import android.view.View
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.AndroidSqlPort
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual AOSP Activity/loopback/Keystore/file/SQL/JNI; OAuth and both responses are fixtures. */
@RunWith(AndroidJUnit4::class)
class SiwcRuntimeDeviceTest {
    private fun await(label:String,timeoutMs:Long=10000,predicate:()->Boolean) {val end=System.currentTimeMillis()+timeoutMs;while(System.currentTimeMillis()<end){if(predicate())return;Thread.sleep(30)};assertTrue(label,predicate())}
    private fun model(a:SiwcRuntimeActivity)=SiwcRuntimeActivity::class.java.getDeclaredField("model").apply{isAccessible=true}.get(a) as SiwcLoginModel
    private fun proof(name:String,value:JSONObject) {val app=InstrumentationRegistry.getInstrumentation().targetContext;val dir=File(app.getExternalFilesDir(null),"siwc-device-evidence").apply{mkdirs()};File(dir,name+".json").writeText(value.put("processId",Process.myPid()).put("actualExternalGrants",0).put("actualAstraCalls",0).toString(2))}
    @Test fun unapprovedAppButtonStaysDisabledWithoutBrowserOrListener() {
        SiwcLoginRuntime.testDependencies=null
        ActivityScenario.launch(SiwcRuntimeActivity::class.java).use{s->
            var ready=false;await("inactive ready"){s.onActivity{ready=model(it).view.value.ready};ready}
            s.onActivity{val v=model(it).view.value;assertFalse(v.enabled);assertFalse(it.window.decorView.findViewWithTag<Button>("siwc_continue").isEnabled);model(it).start();assertFalse(model(it).view.value.busy)}
            s.recreate();s.onActivity{assertFalse(model(it).view.value.enabled);assertFalse(model(it).view.value.signedIn)}
            proof("inactive",JSONObject().put("loginEnabled",false).put("browserOpened",false).put("listenerStarted",false))
        }
    }
    @Test fun syntheticLoopbackSurvivesRotationAndRequestsRealActivityReturn() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val key=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        fun unsigned(b:ByteArray)=if(b[0]==0.toByte())b.copyOfRange(1,b.size) else b
        val rsa=key.public as RSAPublicKey
        val jwks=StrictJson.bytes(mapOf("keys" to listOf(mapOf("kty" to "RSA","alg" to "RS256","kid" to "public-device-key","n" to SiwcOAuthProtocol.encode(unsigned(rsa.modulus.toByteArray())),"e" to SiwcOAuthProtocol.encode(unsigned(rsa.publicExponent.toByteArray()))))))
        val url=AtomicReference<String>();val opens=AtomicInteger();val exchanges=AtomicInteger();val discovery=AtomicInteger();val returns=AtomicInteger()
        val metadata=SiwcOAuthProtocol.Metadata("https://auth.openai.com/.well-known/jwks.json","https://auth.openai.com/api/accounts/oauth/revoke")
        val port=object:SiwcAuthTransport {
            override fun metadata()=metadata.also{discovery.incrementAndGet()}
            override fun jwks(metadata:SiwcOAuthProtocol.Metadata)=jwks
            override fun token(form:Map<String,String>):ByteArray {
                exchanges.incrementAndGet();val u=url.get().toHttpUrl();assertEquals("fixture-device-issued",form["client_id"]);assertEquals(u.queryParameter("redirect_uri"),form["redirect_uri"])
                val now=System.currentTimeMillis()/1000
                val header=SiwcOAuthProtocol.encode(StrictJson.bytes(mapOf("alg" to "RS256","kid" to "public-device-key")))
                val claims=SiwcOAuthProtocol.encode(StrictJson.bytes(mapOf("iss" to SiwcOAuthProtocol.ISSUER,"sub" to "public-device-fixture","aud" to "fixture-device-issued","nonce" to u.queryParameter("nonce"),"iat" to now,"exp" to now+3600)))
                val unsignedJwt="$header.$claims";val sig=Signature.getInstance("SHA256withRSA").run{initSign(key.private);update(unsignedJwt.toByteArray());sign()}
                return StrictJson.bytes(mapOf("access_token" to "PUBLIC_DEVICE_FIXTURE_ACCESS_1234567890","refresh_token" to "PUBLIC_DEVICE_FIXTURE_REFRESH_1234567890","id_token" to unsignedJwt+"."+SiwcOAuthProtocol.encode(sig),"token_type" to "Bearer","scope" to (SiwcOAuthProtocol.IDENTITY+SiwcOAuthProtocol.PLAN).sorted().joinToString(" "),"expires_in" to 3600L))
            }
            override fun revoke(metadata:SiwcOAuthProtocol.Metadata,clientId:String,refreshToken:String)=true
        }
        val androidReturn=SiwcAndroidBrowser(app,SiwcRuntimeActivity::class.java)
        SiwcLoginRuntime.testDependencies={SiwcLoginRuntime.Dependencies(port,object:SiwcBrowserSurface {
            override fun open(value:String){opens.incrementAndGet();url.set(value)}
            override fun requestReturn():Boolean {returns.incrementAndGet();return androidReturn.requestReturn()}
        },{SiwcOAuthConfig("PUBLIC_SYNTHETIC_ANDROID_FIXTURE_ONLY",null,true)})}
        try {ActivityScenario.launch(SiwcRuntimeActivity::class.java).use{s->
            var ready=false;await("fixture enabled"){s.onActivity{ready=model(it).view.value.ready};ready}
            var first:SiwcLoginModel?=null
            s.onActivity{first=model(it);assertTrue(it.window.decorView.findViewWithTag<Button>("siwc_continue").performClick())}
            await("loopback prepared"){url.get()!=null};s.recreate();s.onActivity{assertSame(first,model(it));assertTrue(model(it).view.value.busy)}
            s.moveToState(Lifecycle.State.CREATED)
            val u=url.get().toHttpUrl();val callback=URI(u.queryParameter("redirect_uri"))
            Socket("127.0.0.1",callback.port).use{socket->socket.soTimeout=5000
                val target=callback.path+"?state="+u.queryParameter("state")+"&code=PUBLIC_DEVICE_CODE&client_id=fixture-device-issued"
                socket.getOutputStream().write(("GET $target HTTP/1.1\r\nHost: 127.0.0.1:${callback.port}\r\n\r\n").toByteArray());socket.getOutputStream().flush()
                assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))}
            await("identity committed and return requested"){first!!.view.value.let{it.signedIn && it.returnRequested}}
            await("activity actually resumed"){s.state==Lifecycle.State.RESUMED}
            s.onActivity{assertSame(first,model(it));assertEquals("SIWC_IDENTITY_VERIFIED",model(it).view.value.status)}
            assertEquals(1,opens.get());assertEquals(1,exchanges.get());assertEquals(1,discovery.get());assertEquals(1,returns.get())
            proof("loopback",JSONObject().put("fixtureBrowserOpens",opens.get()).put("fixtureTokenExchanges",exchanges.get()).put("sameModelAfterRotation",true).put("returnRequested",true).put("activityResumed",true).put("loopbackHttp",200))
        }} finally {SiwcLoginRuntime.testDependencies=null}
    }
    @Test fun publicJniUiFileCandidateAndExplicitReviewUseProtectedStore() {
        if(InstrumentationRegistry.getArguments().getString("siwcReplayOnly")=="true") {
            val app=InstrumentationRegistry.getInstrumentation().targetContext
            AndroidLocalSttPilot(app,"siwc-public-v1").use{p->
                val c=p.siwcPublicCandidates();assertEquals(0,p.engine.calls);assertEquals(0,c.getInt("fixtureCompletions"))
                val a=c.getJSONArray("candidates");assertEquals(2,a.length())
                val digests=(0 until a.length()).associate{a.getJSONObject(it).let{x->assertTrue(x.getBoolean("userApproved"));x.getString("id") to x.getString("digest")}}
                val reviewed=p.siwcPublicReview(digests,true);assertEquals(2,reviewed.getJSONArray("receipts").length())
                proof("public-file-reopened",JSONObject().put("actualJniCalls",0).put("fixtureCompletions",0).put("reopenedCandidates",c).put("reviewed",reviewed))
            };return
        }
        ActivityScenario.launch(SiwcPublicResultActivity::class.java).use{s->
            fun phase():Int {var v=-1;s.onActivity{v=it.testState.phase};return v}
            fun next(expected:Int){s.onActivity{assertTrue(it.window.decorView.findViewWithTag<Button>("siwc_public_next").performClick())};await("public phase $expected",150000){phase()==expected}}
            fun pilot():AndroidLocalSttPilot {var p:AndroidLocalSttPilot?=null;s.onActivity{p=it.testState.pilot};return p!!}
            next(1);assertEquals(2,pilot().engine.calls);next(2)
            val scoped=pilot().scoped
            fun count(table:String)=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use{db->if(db.query("SELECT name FROM sqlite_master WHERE name=?",table).isEmpty())0L else db.query("SELECT count(*) n FROM $table").single()["n"] as Long}
            val before=count("workflow_items");next(3)
            assertEquals(2L,count("siwc_native_candidates"));assertEquals(0L,count("siwc_native_user_reviews"));assertEquals(0L,count("workflow_reviewed_summaries"));assertEquals(before,count("workflow_items"))
            var candidate:JSONObject?=null;var owner:SiwcPublicResultModel?=null
            s.onActivity{candidate=it.testState.candidates;owner=it.testState}
            assertEquals(4,candidate!!.getInt("fixtureCompletions"));assertEquals(0,candidate!!.getInt("actualAstraCalls"))
            val frozen=candidate.toString();val samePilot=pilot()
            s.recreate();s.onActivity{assertSame(owner,it.testState);assertSame(samePilot,it.testState.pilot);assertEquals(3,it.testState.phase);assertEquals(frozen,it.testState.candidates.toString());assertTrue(it.window.decorView.findViewWithTag<Button>("siwc_public_next").isEnabled)}
            assertEquals(2,pilot().engine.calls);assertEquals(0L,count("siwc_native_user_reviews"));assertEquals(0L,count("workflow_reviewed_summaries"));assertEquals(before,count("workflow_items"))
            next(4);assertEquals(2L,count("siwc_native_user_reviews"));assertEquals(2L,count("workflow_reviewed_summaries"));assertEquals(before+2,count("workflow_items"));assertEquals(2,pilot().engine.calls)
            s.recreate();s.onActivity{assertSame(owner,it.testState);assertEquals(4,it.testState.phase);assertFalse(it.window.decorView.findViewWithTag<Button>("siwc_public_next").isEnabled)}
            assertEquals(2,pilot().engine.calls);assertEquals(before+2,count("workflow_items"))
            proof("public-file",JSONObject().put("candidateAndReviewedPhasesSurviveRecreation",true).put("actualJniCalls",pilot().engine.calls).put("fixtureCompletions",4).put("candidatesBeforeReview",2).put("userReviewsAfter",2).put("itemsBefore",before).put("itemsAfter",count("workflow_items")).put("candidateOnlyBeforeUserClick",true).put("fixtureResult",candidate))
        }
    }
}
