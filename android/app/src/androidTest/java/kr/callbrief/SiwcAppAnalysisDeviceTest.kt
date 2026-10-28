package kr.callbrief

import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicInteger

/** Actual app buttons/JNI/vault/file/native review; the OIDC server and inference are fixtures.
 * This test must use a separate synthetic AVD, never the real-grant trial device. */
@RunWith(AndroidJUnit4::class)
class SiwcAppAnalysisDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val posts=AtomicInteger()
    private val offlineAuth=object:SiwcAuthTransport {
        override fun metadata()=SiwcOAuthProtocol.Metadata("https://auth.openai.com/.well-known/jwks.json","https://auth.openai.com/api/accounts/oauth/revoke")
        override fun jwks(metadata:SiwcOAuthProtocol.Metadata)=error("FIXTURE_NO_JWKS_REQUEST")
        override fun token(form:Map<String,String>)=error("FIXTURE_NO_TOKEN_REQUEST")
        override fun revoke(metadata:SiwcOAuthProtocol.Metadata,clientId:String,refreshToken:String)=true
    }
    @After fun cleanup(){SiwcAppAnalysisRuntime.testDependencies=null}
    private fun await(label:String,predicate:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+150000;while(android.os.SystemClock.elapsedRealtime()<end){if(predicate())return;Thread.sleep(50)};fail("SIWC_APP_UI_WAIT_$label")}
    private fun seedFixtureIdentity(){
        val keys=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair();val rsa=keys.public as RSAPublicKey
        fun unsigned(b:ByteArray)=if(b[0]==0.toByte())b.copyOfRange(1,b.size) else b
        val jwks=StrictJson.bytes(mapOf("keys" to listOf(mapOf("kty" to "RSA","alg" to "RS256","kid" to "app-ui-fixture","n" to SiwcOAuthProtocol.encode(unsigned(rsa.modulus.toByteArray())),"e" to SiwcOAuthProtocol.encode(unsigned(rsa.publicExponent.toByteArray()))))))
        lateinit var pending:SiwcOAuthProtocol.Pending
        val metadata=SiwcOAuthProtocol.Metadata("https://auth.openai.com/.well-known/jwks.json","https://auth.openai.com/api/accounts/oauth/revoke")
        val auth=object:SiwcAuthTransport {
            override fun metadata()=metadata
            override fun jwks(metadata:SiwcOAuthProtocol.Metadata)=jwks
            override fun token(form:Map<String,String>):ByteArray {
                val now=System.currentTimeMillis()/1000
                val header=SiwcOAuthProtocol.encode(StrictJson.bytes(mapOf("alg" to "RS256","kid" to "app-ui-fixture")))
                val claims=SiwcOAuthProtocol.encode(StrictJson.bytes(mapOf("iss" to SiwcOAuthProtocol.ISSUER,"sub" to "public-app-ui-fixture","email" to "app-ui@example.invalid","aud" to "fixture-app-client","nonce" to pending.nonce,"iat" to now,"exp" to now+3600)))
                val value="$header.$claims";val sig=Signature.getInstance("SHA256withRSA").run{initSign(keys.private);update(value.toByteArray());sign()}
                return StrictJson.bytes(mapOf("access_token" to "PUBLIC_APP_UI_FIXTURE_ACCESS_1234567890","refresh_token" to "PUBLIC_APP_UI_FIXTURE_REFRESH_1234567890","id_token" to value+"."+SiwcOAuthProtocol.encode(sig),"token_type" to "Bearer","scope" to pending.requestedScopes.sorted().joinToString(" "),"expires_in" to 3600L))
            }
            override fun revoke(metadata:SiwcOAuthProtocol.Metadata,clientId:String,refreshToken:String)=true
        }
        SiwcSessionStore(app,auth).use{s->
            check(s.registrations().all{it.subject=="public-app-ui-fixture"}) { "FIXTURE_AVD_MUST_NOT_CONTAIN_REAL_ACCOUNTS" }
            pending=s.begin(SiwcOAuthConfig("PUBLIC_APP_UI_FIXTURE", "fixture-app-client",false),"http://127.0.0.1:1455/auth/callback",null,true)
            val callback=pending.redirect+"?state="+pending.state+"&code=PUBLIC_APP_UI_CODE&client_id=fixture-app-client"
            s.finish(pending,pending.callback(callback,System.currentTimeMillis()),metadata)
            assertTrue(s.audit()["active"]==true)
        }
    }
    private fun installFixture(){
        SiwcSessionStore(app,offlineAuth).use{s->check(s.registrations().isNotEmpty() && s.registrations().all{it.subject=="public-app-ui-fixture"}) { "FIXTURE_ACCOUNT_REQUIRED" }}
        SiwcAppAnalysisRuntime.testDependencies={context->SiwcAppAnalysisRuntime.Dependencies(true,true){
        SiwcAppAnalysisBackend(context){contract->
            val delegate=SiwcPublicFixturePort(contract)
            object:SiwcInferencePort {
                override fun models(scope:String)=delegate.models(scope)
                override fun invoke(request:SiwcAstraProtocol.Invocation,stopped:()->Boolean):SiwcAstraProtocol.Completion {posts.incrementAndGet();return delegate.invoke(request,stopped)}
            }
        }
    }}}
    private fun counts():Map<String,Long> =AndroidLocalSttPilot(app,"siwc-app-fixture-v1",true).use{p->
        AndroidSqlPort(p.scoped,GeneralLocalStore.DATABASE,p.scoped.assets.open("call_core_schema.sql")).use{db->
            listOf("siwc_native_candidates","siwc_native_user_reviews","workflow_reviewed_summaries","workflow_items").associateWith{table->
                if(db.query("SELECT name FROM sqlite_master WHERE name=?",table).isEmpty())0L else db.query("SELECT count(*) n FROM $table").single()["n"] as Long
            }
        }
    }
    private fun proof(name:String,result:JSONObject){val d=File(app.getExternalFilesDir(null),"siwc-app-device-evidence").apply{mkdirs()};File(d,"$name.json").writeText(result.put("actualExternalGrants",0).put("actualAstraCalls",0).put("fixtureInvocations",posts.get()).toString(2))}
    @Test fun normalConnectionButtonOpensDisabledDefaultAppWithoutStartingAStage(){
        assumeFalse(BuildConfig.SIWC_PERSONAL_TRIAL_APPROVED);SiwcAppAnalysisRuntime.testDependencies=null
        ActivityScenario.launch(ConnectionCenterActivity::class.java).use{s->
            s.onActivity{assertTrue(it.window.decorView.findViewWithTag<Button>("siwc_app_analysis").performClick())}
            var screen:SiwcAppAnalysisActivity?=null
            await("NORMAL_ENTRY"){InstrumentationRegistry.getInstrumentation().runOnMainSync{screen=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<SiwcAppAnalysisActivity>().singleOrNull()};screen!=null}
            InstrumentationRegistry.getInstrumentation().runOnMainSync{val a=screen!!;val v=a.testState.view.value;assertFalse(v.enabled);assertFalse(v.busy);assertEquals("START",v.phase);assertNull(v.data);assertFalse(a.window.decorView.findViewWithTag<Button>("siwc_app_restore").isEnabled);a.finish()}
            proof("DEFAULT_DISABLED",JSONObject().put("normalConnectionEntry",true).put("enabled",false).put("stageStarted",false))
        }
    }
    @Test fun normalAppControlsPerformPublicFileReviewAndReadback(){
        assumeTrue(BuildConfig.DEBUG && BuildConfig.SIWC_PERSONAL_TRIAL_APPROVED && InstrumentationRegistry.getArguments().getString("siwcAppFixture")=="PUBLIC_ONLY")
        val replay=InstrumentationRegistry.getArguments().getString("siwcAppReplayOnly")=="true"
        if(!replay)seedFixtureIdentity()
        installFixture()
        ActivityScenario.launch(SiwcAppAnalysisActivity::class.java).use{s->
            fun view():SiwcAppAnalysisModel.View {var v:SiwcAppAnalysisModel.View?=null;s.onActivity{v=it.testState.view.value};return v!!}
            fun click(tag:String){s.onActivity{assertTrue(it.window.decorView.findViewWithTag<Button>(tag).performClick())}}
            fun next(tag:String,phase:String,confirm:Boolean=false){if(confirm)s.onActivity{it.window.decorView.findViewWithTag<CheckBox>("siwc_app_confirm").isChecked=true};click(tag);await(phase){view().let{!it.busy && it.phase==phase}}}
            if(replay){
                val before=counts();next("siwc_app_restore","REVIEWED");val after=counts();assertEquals(before,after);assertEquals(0,posts.get());assertEquals(0,view().data!!.getInt("actualAndroidSTTCalls"))
                s.onActivity{assertTrue(it.window.decorView.findViewWithTag<TextView>("siwc_app_saved_summary").text.isNotBlank())}
                proof("REOPENED",JSONObject().put("countsBefore",JSONObject(before)).put("countsAfter",JSONObject(after)).put("jniCalls",0).put("summaryReceipt",view().data!!.getString("summaryReceipt")));return
            }
            next("siwc_app_transcribe","TRANSCRIBED");assertEquals(1,view().data!!.getInt("actualAndroidSTTCalls"));assertEquals(0,posts.get())
            next("siwc_app_bind","BOUND",true);next("siwc_app_plan","ANALYSIS_READY");assertEquals(0,posts.get())
            var owner:SiwcAppAnalysisModel?=null;s.onActivity{owner=it.testState};s.recreate();s.onActivity{assertSame(owner,it.testState);assertFalse(it.window.decorView.findViewWithTag<Button>("siwc_app_analyse").isEnabled)}
            next("siwc_app_analyse","ANALYSIS_DONE",true);assertEquals(1,posts.get())
            next("siwc_app_context_plan","CONTEXT_READY");assertEquals(1,posts.get());next("siwc_app_context","CONTEXT_DONE",true);assertEquals(2,posts.get())
            next("siwc_app_receive","CANDIDATE");val before=counts();assertEquals(1L,before["siwc_native_candidates"]);assertEquals(0L,before["siwc_native_user_reviews"]);assertEquals(0L,before["workflow_reviewed_summaries"])
            val frozen=view().data!!.getJSONObject("candidate").getString("digest");s.recreate();s.onActivity{assertSame(owner,it.testState)};assertEquals("CANDIDATE",view().phase);assertEquals(frozen,view().data!!.getJSONObject("candidate").getString("digest"));assertEquals(2,posts.get())
            if(InstrumentationRegistry.getArguments().getString("siwcAppVariant")=="LOGOUT_AFTER_CANDIDATE"){
                SiwcSessionStore(app,offlineAuth).use{it.signOut(it.activeKey())}
                next("siwc_app_review","HELD",true);assertEquals(before,counts());assertEquals(2,posts.get())
                proof("LOGOUT_FENCED",JSONObject().put("candidateCount",1).put("userReviews",0).put("userSummaries",0).put("countsBefore",JSONObject(before)).put("countsAfter",JSONObject(counts())).put("phase",view().phase));return
            }
            next("siwc_app_review","REVIEWED",true);val after=counts();assertEquals(1L,after["siwc_native_user_reviews"]);assertEquals(1L,after["workflow_reviewed_summaries"]);assertEquals(before.getValue("workflow_items")+1,after["workflow_items"])
            val receipt=view().data!!.getString("summaryReceipt");s.recreate();assertEquals("REVIEWED",view().phase);next("siwc_app_restore","REVIEWED");assertEquals(receipt,view().data!!.getString("summaryReceipt"));assertEquals(after,counts());assertEquals(2,posts.get())
            proof("INITIAL",JSONObject().put("jniCalls",1).put("countsBeforeUserReview",JSONObject(before)).put("countsAfterUserReview",JSONObject(after)).put("sameOwnerAfterRecreation",true).put("sameCandidateDigestAfterRecreation",true).put("summaryReceipt",receipt))
        }
    }
}
