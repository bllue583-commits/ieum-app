package kr.callbrief

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private fun field(a:Any,name:String)=a.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun click(a:AndroidLocalSttActivity,tag:String){views(a.findViewById(android.R.id.content)).filterIsInstance<Button>().single{it.tag==tag}.performClick()}
    private fun stopped(p:AndroidLocalSttPilot)=(field(p,"stop") as AtomicBoolean).get()
    private fun waitFor(s:ActivityScenario<AndroidLocalSttActivity>,timeout:Long=60000,predicate:(AndroidLocalSttActivity)->Boolean){val end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){var done=false;s.onActivity{done=predicate(it)};if(done)return;Thread.sleep(50)};fail("PUBLIC_G01_UI_TIMEOUT")}
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
        }
    }
}
