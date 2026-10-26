package kr.callbrief

import android.os.Looper
import android.widget.Button
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SiwcLoginUiTest:ProtectedTextHostTestBase() {
    @After fun cleanup(){SiwcLoginRuntime.testDependencies=null}
    private fun await(predicate:()->Boolean) {val until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();if(predicate())return;Thread.sleep(10)};assertTrue(predicate())}
    @Test fun defaultLoginIsDisabledAndResumeDoesNotPrompt() {
        val c=Robolectric.buildActivity(SiwcRuntimeActivity::class.java).setup();val a=c.get()
        await{a.window.decorView.findViewWithTag<Button>("siwc_continue")!=null}
        val button=a.window.decorView.findViewWithTag<Button>("siwc_continue");assertFalse(button.isEnabled);button.performClick()
        c.pause().stop().start().resume();shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(a).nextStartedActivity);assertFalse(a.window.decorView.findViewWithTag<Button>("siwc_continue").isEnabled)
        c.pause().stop().destroy()
    }
    @Test fun nullApprovalNeverDiscoversOrOpensEvenOnClick() {
        val calls=AtomicInteger()
        SiwcLoginRuntime.testDependencies={SiwcLoginRuntime.Dependencies(object:SiwcAuthTransport {
            override fun metadata():SiwcOAuthProtocol.Metadata {calls.incrementAndGet();error("MUST_NOT_DISCOVER")}
            override fun jwks(metadata:SiwcOAuthProtocol.Metadata)=error("MUST_NOT_FETCH")
            override fun token(form:Map<String,String>)=error("MUST_NOT_EXCHANGE")
            override fun revoke(metadata:SiwcOAuthProtocol.Metadata,clientId:String,refreshToken:String)=error("MUST_NOT_REVOKE")
        },object:SiwcBrowserSurface {override fun open(url:String){calls.incrementAndGet()};override fun requestReturn()=false},{null})}
        val c=Robolectric.buildActivity(SiwcRuntimeActivity::class.java).setup();val a=c.get()
        val field=SiwcRuntimeActivity::class.java.getDeclaredField("model").apply{isAccessible=true};val model=field.get(a) as SiwcLoginModel
        await{model.view.value.ready};model.start();model.resume();Thread.sleep(50);shadowOf(Looper.getMainLooper()).idle()
        assertEquals("SIWC_APPROVAL_REQUIRED",model.view.value.status);assertEquals(0,calls.get());assertFalse(model.view.value.signedIn)
        c.pause().stop().destroy()
    }
}
