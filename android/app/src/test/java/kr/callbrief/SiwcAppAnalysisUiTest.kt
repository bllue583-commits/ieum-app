package kr.callbrief

import android.content.res.Configuration
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import androidx.lifecycle.ViewModelStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controller/UI fixtures only. Real JNI/file/Store gates have separate integration tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SiwcAppAnalysisUiTest {
    private val stores=mutableListOf<ViewModelStore>()
    @After fun cleanup(){SiwcAppAnalysisRuntime.testDependencies=null;stores.forEach{it.clear()}}
    private fun await(predicate:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(predicate())return;Thread.sleep(10)};assertTrue(predicate())}
    private class Port:SiwcAppAnalysisPort {
        val actions=CopyOnWriteArrayList<String>();var blocks=false;val entered=CountDownLatch(1);val released=CountDownLatch(1);@Volatile var stopped=false
        override fun run(action:String,key:String,candidateId:String,digest:String,confirmed:Boolean):JSONObject {
            actions+=action
            if(blocks && action=="ANALYSE"){entered.countDown();check(released.await(5,TimeUnit.SECONDS))}
            return JSONObject().put("phase",when(action){"TRANSCRIBE"->"TRANSCRIBED";"BIND"->"BOUND";"PLAN"->"ANALYSIS_READY";"ANALYSE","RESTORE"->"ANALYSIS_DONE";"CONTEXT_PLAN"->"CONTEXT_READY";"CONTEXT"->"CONTEXT_DONE";"RECEIVE"->"CANDIDATE";else->"REVIEWED"})
                .put("stages",JSONArray().put(JSONObject().put("transcript","PUBLIC_UI_TRANSCRIPT")))
                .put("analysisKey","public-analysis-key").put("contextKey","public-context-key")
                .put("model","gpt-6-astra").put("requestedEffort","xhigh").put("contextModel","gpt-6-astra").put("contextEffort","xhigh")
                .put("publicSourcePack",JSONObject()).put("approvedSnapshot",JSONObject()).put("manifestSha256","a".repeat(64))
                .put("progress",JSONObject().put("analysis",JSONObject().put("output","{}")))
                .put("candidate",JSONObject().put("id","public-candidate").put("digest","public-digest").put("artifact",JSONObject().put("analysis",JSONObject().put("summary","PUBLIC_CANDIDATE"))).put("candidate",JSONObject()))
                .put("summaryReceipt","public-user-receipt")
        }
        override fun requestStop(){stopped=true;released.countDown()}
        override fun close(){requestStop()}
    }
    private fun model(port:Port,enabled:Boolean=true):SiwcAppAnalysisModel=SiwcAppAnalysisModel(SiwcAppAnalysisRuntime.Dependencies(enabled,true){port}).also{m->stores+=ViewModelStore().apply{put("model",m)}}
    private fun act(m:SiwcAppAnalysisModel,action:String,confirmed:Boolean=false,key:String=""){m.act(action,confirmed,key);await{!m.view.value.busy}}
    private fun ready(m:SiwcAppAnalysisModel){act(m,"TRANSCRIBE");act(m,"BIND",true);act(m,"PLAN")}
    @Test fun disabledOrFreshModelDoesNotCreatePortOrStartAnything(){
        var created=0;val m=SiwcAppAnalysisModel(SiwcAppAnalysisRuntime.Dependencies(false,false){created++;Port()});stores+=ViewModelStore().apply{put("m",m)}
        m.act("TRANSCRIBE");m.act("RESTORE");shadowOf(Looper.getMainLooper()).idle();assertEquals(0,created);assertEquals("START",m.view.value.phase)
    }
    @Test fun eachStageRequiresItsOwnExactKeyAndExplicitConfirmation(){
        val p=Port();val m=model(p);m.act("ANALYSE",true,"public-analysis-key");assertTrue(p.actions.isEmpty());ready(m)
        m.act("ANALYSE",false,"public-analysis-key");m.act("ANALYSE",true,"wrong-key");assertEquals(listOf("TRANSCRIBE","BIND","PLAN"),p.actions)
        act(m,"ANALYSE",true,"public-analysis-key");m.act("ANALYSE",true,"public-analysis-key");act(m,"CONTEXT_PLAN")
        m.act("CONTEXT",true,"public-analysis-key");m.act("CONTEXT",false,"public-context-key");assertEquals(0,p.actions.count{it=="CONTEXT"})
        act(m,"CONTEXT",true,"public-context-key");assertEquals(1,p.actions.count{it=="ANALYSE"});assertEquals(1,p.actions.count{it=="CONTEXT"})
    }
    @Test fun fileCandidateNeverRunsUserReviewWithoutAnotherCommand(){
        val p=Port();val m=model(p);ready(m);act(m,"ANALYSE",true,"public-analysis-key");act(m,"CONTEXT_PLAN");act(m,"CONTEXT",true,"public-context-key");act(m,"RECEIVE")
        assertEquals("CANDIDATE",m.view.value.phase);assertFalse("REVIEW" in p.actions)
        m.act("REVIEW",false);assertFalse("REVIEW" in p.actions);act(m,"REVIEW",true);assertEquals("REVIEWED",m.view.value.phase);assertEquals(1,p.actions.count{it=="REVIEW"})
    }
    @Test fun cancelledLateCompletionCannotAdvanceOrReceiveAFile(){
        val p=Port().apply{blocks=true};val m=model(p);ready(m);m.act("ANALYSE",true,"public-analysis-key");assertTrue(p.entered.await(2,TimeUnit.SECONDS))
        m.cancel();await{!m.view.value.busy};assertTrue(p.stopped);assertEquals("HELD",m.view.value.phase);assertEquals("",m.view.value.approvalKey)
        m.act("RECEIVE");m.act("ANALYSE",true,"public-analysis-key");assertFalse("RECEIVE" in p.actions);assertEquals(1,p.actions.count{it=="ANALYSE"})
    }
    @Test fun explicitRestoreReadsOnlyItsRestoreCommand(){val p=Port();val m=model(p);act(m,"RESTORE");assertEquals(listOf("RESTORE"),p.actions);assertEquals("ANALYSIS_DONE",m.view.value.phase)}
    @Test fun recreatedPlanKeepsOwnerButDoesNotCarryCheckboxApproval(){
        val p=Port();SiwcAppAnalysisRuntime.testDependencies={SiwcAppAnalysisRuntime.Dependencies(true,true){p}}
        val c=Robolectric.buildActivity(SiwcAppAnalysisActivity::class.java).setup();val m=c.get().testState;ready(m);shadowOf(Looper.getMainLooper()).idle()
        c.get().window.decorView.findViewWithTag<CheckBox>("siwc_app_confirm").isChecked=true
        assertTrue(c.get().window.decorView.findViewWithTag<Button>("siwc_app_analyse").isEnabled)
        val old=c.get();val rotated=Configuration(old.resources.configuration).apply{orientation=if(orientation==Configuration.ORIENTATION_PORTRAIT)Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT}
        c.configurationChange(rotated);shadowOf(Looper.getMainLooper()).idle();assertSame(m,c.get().testState);assertEquals("ANALYSIS_READY",m.view.value.phase)
        assertFalse(c.get().window.decorView.findViewWithTag<CheckBox>("siwc_app_confirm").isChecked);assertFalse(c.get().window.decorView.findViewWithTag<Button>("siwc_app_analyse").isEnabled);assertFalse("ANALYSE" in p.actions)
        c.get().finish();c.pause().stop().destroy()
    }
}
