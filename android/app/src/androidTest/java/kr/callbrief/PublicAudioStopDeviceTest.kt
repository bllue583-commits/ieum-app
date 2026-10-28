package kr.callbrief

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** G01 actual Activity button and public JNI, in a fresh synthetic AVD. No direct cancel() call
 * substitutes for the user control being tested. Each method requires a fresh public sandbox. */
@RunWith(AndroidJUnit4::class)
class PublicAudioStopDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun field(a:Any,name:String):Any? {val owner=if(a is AndroidLocalSttActivity)a.testState else a;return owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)}
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun click(a:AndroidLocalSttActivity,tag:String){views(a.findViewById(android.R.id.content)).filterIsInstance<Button>().single{it.tag==tag}.performClick()}
    private fun stopped(p:AndroidLocalSttPilot)=(field(p,"stop") as AtomicBoolean).get()
    private fun waitFor(s:ActivityScenario<AndroidLocalSttActivity>,timeout:Long=60000,predicate:(AndroidLocalSttActivity)->Boolean){val start=android.os.SystemClock.elapsedRealtime();val timeline=org.json.JSONArray();var previous=""
        var unexpectedlyIdle=false
        while(android.os.SystemClock.elapsedRealtime()-start<timeout){var done=false;s.onActivity{a->
            done=predicate(a)
            unexpectedlyIdle=!done && field(a,"running")==false
            val state=JSONObject().put("activityInstance",System.identityHashCode(a)).put("changingConfigurations",a.isChangingConfigurations).put("running",field(a,"running")).put("step",field(a,"integrationStep")).put("startedJNI",(field(a,"integrationPilot") as? AndroidLocalSttPilot)?.engine?.calls ?: 0)
            if(state.toString()!=previous || done){previous=state.toString();timeline.put(state.put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start))}
        };if(done){proof("wait-complete-"+System.currentTimeMillis(),JSONObject().put("timeline",timeline));return};if(unexpectedlyIdle)break;Thread.sleep(50)}
        val diagnostic=JSONObject()
        var inspectedPilot:AndroidLocalSttPilot?=null
        s.onActivity { a ->
            diagnostic.put("running",field(a,"running")).put("integrationStep",field(a,"integrationStep"))
                .put("publicScreenText",org.json.JSONArray(views(a.findViewById(android.R.id.content)).filterIsInstance<TextView>().map{it.text.toString()}))
            (field(a,"integrationPilot") as? AndroidLocalSttPilot)?.let { p ->
                inspectedPilot=p
                diagnostic.put("startedJNI",p.engine.calls).put("cancelled",stopped(p))
            }
        }
        // Do not block the UI thread on Keystore/SQL, or wait for a database held by active JNI.
        inspectedPilot?.takeIf{diagnostic.opt("running")==false}?.let{p->AndroidSqlPort(p.scoped,GeneralLocalStore.DATABASE,p.scoped.assets.open("call_core_schema.sql")).use{db->
            if(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='unclassified_audio_requests'").isNotEmpty())
                diagnostic.put("requests",org.json.JSONArray(db.query("SELECT state,reason FROM unclassified_audio_requests").map{JSONObject(it)}))
        }}
        diagnostic.put("timeline",timeline).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start)
        diagnostic.put("threads",org.json.JSONArray(Thread.getAllStackTraces().entries.filter{it.value.any{f->f.className.startsWith("kr.callbrief.")}}.map{(t,frames)->JSONObject().put("name",t.name).put("state",t.state.name).put("stack",org.json.JSONArray(frames.map{it.toString()}))}))
        proof("timeout-"+System.currentTimeMillis(),diagnostic)
        fail("${if(unexpectedlyIdle)"PUBLIC_G01_IDLE_BEFORE_EXPECTED_STAGE" else "PUBLIC_G01_UI_TIMEOUT"}: running=${diagnostic.opt("running")}, step=${diagnostic.opt("integrationStep")}, JNI=${diagnostic.opt("startedJNI")}, requests=${diagnostic.opt("requests")}")
    }
    private fun count(p:AndroidLocalSttPilot,table:String)=AndroidSqlPort(p.scoped,GeneralLocalStore.DATABASE,p.scoped.assets.open("call_core_schema.sql")).use{db->if(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",table).isEmpty())0 else (db.query("SELECT count(*) n FROM $table").single()["n"] as Number).toInt()}
    private fun proof(name:String,x:JSONObject){val d=File(context.getExternalFilesDir(null),"g01-device-evidence").apply{mkdirs()};File(d,"$name.json").writeText(x.toString(2))}
    @Test fun integrationAnalysisStopButtonCancelsActiveNativeWork() {
        ActivityScenario.launch<AndroidLocalSttActivity>(Intent(context,AndroidLocalSttActivity::class.java)).use{s->
            s.onActivity{click(it,"integrated_public_run")};waitFor(s){a->(field(a,"integrationPilot") as? AndroidLocalSttPilot)?.engine?.calls?.let{it>0}==true}
            lateinit var p:AndroidLocalSttPilot
            s.onActivity{a->p=field(a,"integrationPilot") as AndroidLocalSttPilot;click(a,"android_local_stt_stop")}
            val cancelled=stopped(p);proof("analysis-stop",JSONObject().put("buttonClicked",true).put("activeIntegrationCancelled",cancelled).put("startedJNI",p.engine.calls))
            assertTrue("G01: stop button must cancel the active integration pilot",cancelled)
            waitFor(s,30000){field(it,"running")==false}
            assertEquals(0,count(p,"unclassified_audio_results"));assertEquals(0,count(p,"integrated_user_reviews"));assertEquals(0,count(p,"automation_results"))
            s.onActivity{assertEquals("cancelled analysis must not advance",0,field(it,"integrationStep"))}
            proof("analysis-stop-settled",JSONObject().put("cancelled",stopped(p)).put("step",0).put("newTranscripts",count(p,"unclassified_audio_results")).put("newUserReviews",count(p,"integrated_user_reviews")).put("newNativeResults",count(p,"automation_results")))
        }
    }
    @Test fun integrationContentReviewStopButtonPreventsUserReviewCommit() {
        ActivityScenario.launch<AndroidLocalSttActivity>(Intent(context,AndroidLocalSttActivity::class.java)).use{s->
            s.onActivity{click(it,"integrated_public_run")};waitFor(s){field(it,"running")==false && field(it,"integrationStep")==1}
            s.onActivity{click(it,"integrated_public_run")};waitFor(s){field(it,"running")==false && field(it,"integrationStep")==2}
            lateinit var p:AndroidLocalSttPilot
            s.onActivity{a->p=field(a,"integrationPilot") as AndroidLocalSttPilot;click(a,"integrated_public_run");click(a,"android_local_stt_stop")}
            val cancelled=stopped(p);proof("review-stop",JSONObject().put("buttonClicked",true).put("activeIntegrationCancelled",cancelled).put("startedJNI",p.engine.calls))
            assertTrue("G01: content review cancellation must target the integration pilot",cancelled)
            waitFor(s,30000){field(it,"running")==false}
            assertEquals(0,count(p,"integrated_user_reviews"));assertEquals(0,count(p,"workflow_reviewed_summaries"))
            s.onActivity{assertEquals("cancelled review must not advance",2,field(it,"integrationStep"))}
            proof("review-stop-settled",JSONObject().put("cancelled",stopped(p)).put("step",2).put("newUserReviews",count(p,"integrated_user_reviews")).put("newUserSummaries",count(p,"workflow_reviewed_summaries")))
        }
    }
    @Test fun recreatedScreenStopsTheSameActiveNativeOperation() {
        ActivityScenario.launch<AndroidLocalSttActivity>(Intent(context,AndroidLocalSttActivity::class.java)).use{s->
            s.onActivity{click(it,"integrated_public_run")};waitFor(s){a->a.testState.integrationPilot?.engine?.calls?.let{it>0}==true}
            lateinit var owner:AndroidLocalSttModel;lateinit var p:AndroidLocalSttPilot
            s.onActivity{owner=it.testState;p=checkNotNull(owner.integrationPilot)}
            s.recreate()
            s.onActivity{a->assertSame(owner,a.testState);assertSame(p,a.testState.integrationPilot);assertEquals(0,a.testState.integrationStep);click(a,"android_local_stt_stop")}
            assertTrue(stopped(p));waitFor(s,30000){!it.testState.running}
            assertEquals(0,count(p,"unclassified_audio_results"));assertEquals(0,count(p,"integrated_user_reviews"));assertEquals(0,count(p,"automation_results"))
            proof("recreated-analysis-stop",JSONObject().put("sameOwner",true).put("samePilot",true).put("activeIntegrationCancelled",stopped(p)).put("startedJNI",p.engine.calls).put("newTranscripts",0).put("newUserReviews",0).put("newNativeResults",0))
        }
    }
}
