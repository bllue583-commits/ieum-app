package kr.callbrief

import android.content.res.Configuration
import android.os.Looper
import android.widget.Button
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** UI state fixtures only; the device tests separately use real JNI, candidates and receipts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PublicAudioLifecycleTest {
    private fun set(owner:Any,name:String,value:Any){owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(owner,value)}
    private fun rotated(configuration:Configuration)=Configuration(configuration).apply {
        orientation=if(orientation==Configuration.ORIENTATION_LANDSCAPE)Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
    }
    @Test fun reviewCandidatePhaseAndExactContentSurviveConfigurationChange() {
        val c=Robolectric.buildActivity(SiwcPublicResultActivity::class.java).setup();val old=c.get();val model=old.testState
        val candidate=JSONObject().put("candidates",JSONArray())
        set(model,"phase",3);set(model,"transcripts","PUBLIC_UI_FIXTURE");set(model,"candidates",candidate);model.changes.value++
        shadowOf(Looper.getMainLooper()).idle()
        c.configurationChange(rotated(old.resources.configuration));val recreated=c.get();shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(old,recreated);assertSame(model,recreated.testState);assertEquals(3,recreated.testState.phase)
        assertSame(candidate,recreated.testState.candidates);assertEquals("PUBLIC_UI_FIXTURE",recreated.testState.transcripts)
        assertNull(model.pilot);assertFalse(model.busy);assertTrue(recreated.window.decorView.findViewWithTag<Button>("siwc_public_next").isEnabled)
        recreated.finish();c.pause().stop().destroy()
    }
    @Test fun recreationKeepsCancellationOwnerAndFinishCancelsItOnce() {
        val c=Robolectric.buildActivity(AndroidLocalSttActivity::class.java).setup();val old=c.get();val model=old.testState
        var cancelled=0;val operation=PublicAudioOperation().apply{attach{cancelled++}}
        set(model,"activeOperation",operation);set(model,"running",true);model.changes.value++;shadowOf(Looper.getMainLooper()).idle()
        c.configurationChange(rotated(old.resources.configuration));val recreated=c.get();shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(old,recreated);assertSame(model,recreated.testState);assertTrue(model.running);assertEquals(0,cancelled)
        assertTrue(recreated.window.decorView.findViewWithTag<Button>("android_local_stt_stop").performClick())
        assertEquals(1,cancelled);recreated.finish();c.pause().stop().destroy();assertEquals(1,cancelled)
    }
    @Test fun constructionAndResumeDoNotStartOrApproveAnyStage() {
        val c=Robolectric.buildActivity(SiwcPublicResultActivity::class.java).setup();val model=c.get().testState
        c.pause().stop().start().resume();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,model.phase);assertFalse(model.busy);assertNull(model.pilot);assertNull(model.candidates)
        c.get().finish();c.pause().stop().destroy()
    }
}
