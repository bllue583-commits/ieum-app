package kr.callbrief.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Pictures of the home screen while 처음 정리 rests (#20), for a person to look at (not a check): runs only when
 * IEUM_SHOTS_DIR is set and writes one PNG per state there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h852dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRestShotsTest:ProtectedTextHostTestBase() {
    private val out=System.getenv("IEUM_SHOTS_DIR")
    private fun idle() { repeat(30) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) } }
    private fun shoot(name:String,f:HomeActivity.FirstPassView) {
        val a=Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        // Let the screen's own first read land (it shows the setup card here) before drawing the state to look at.
        var tries=0
        while(tries++<100 && a.window.decorView.findViewWithTag<View>("home_setup")==null) idle()
        idle()
        a.render(HomeActivity.Snapshot(true,true,"READY",2,0,1,0,0,1_793_300_000_000,firstPass=f));idle()
        val dm=a.resources.displayMetrics;val w=dm.widthPixels;val h=dm.heightPixels
        val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val page=a.window.decorView
        page.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));page.layout(0,0,w,h);page.draw(Canvas(bmp))
        java.io.File(out!!).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun restingOnBattery() {
        assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!out.isNullOrBlank())
        shoot("1_rest_battery",HomeActivity.FirstPassView(37,9.0,4,2,audioMinutesLeft=190,lowBattery=true))
        shoot("2_rest_results",HomeActivity.FirstPassView(0,null,6,0,stalled=true))
        shoot("3_running",HomeActivity.FirstPassView(37,9.0,4,2,audioMinutesLeft=190))
        shoot("4_rest_battery_short",HomeActivity.FirstPassView(3,0.5,0,0,audioMinutesLeft=25,lowBattery=true,mailConnected=false))
    }
    @Config(qualifiers="w393dp-h852dp-night-xxhdpi")
    @Test fun restingAtNight() {
        assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!out.isNullOrBlank())
        shoot("5_rest_battery_night",HomeActivity.FirstPassView(37,9.0,4,2,audioMinutesLeft=190,lowBattery=true))
    }
}
