package kr.callbrief.pipeline.call

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.CallUi
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** v3.3 (DESIGN_V4 3.3-3): pictures of the call card in four ways (light and dark, letters 1.0 and 1.3 times), for a person to look
 * at, not a check: runs only when IEUM_SHOTS_DIR is set (the device harness sets it to evidence/shots) and writes
 * card_{light,dark}_{10,13}.png there. Made-up names only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h852dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallCardShotsTest:ProtectedTextHostTestBase() {
    private val out=System.getenv("IEUM_SHOTS_DIR")
    private val app get()=RuntimeEnvironment.getApplication()
    @After fun back() { RuntimeEnvironment.setFontScale(1.0f);RuntimeEnvironment.setQualifiers("+notnight");CallUi.applyTheme(app) }

    private fun content()=CallCardView.Content("김민수","탄탄이엔씨, 철근","지난 통화 어제 오후 3:00, 받은 전화","지난 통화",
        "견적서는 금요일까지 메일로 보내 드리기로 했어요.",
        listOf(CallCardView.Content.Task("견적서 보내기","어제 요청"),CallCardView.Content.Task("30층 타설 날짜 다시 확인하기","10월 30일 요청",unsure=true)),
        "철근 단가 재견적: 업체 회신 대기",summaryUnsure=false)

    private fun shoot(name:String,night:Boolean,scale:Float):Int {
        RuntimeEnvironment.setQualifiers(if(night) "+night" else "+notnight");RuntimeEnvironment.setFontScale(scale);CallUi.applyTheme(app)
        val v=CallCardView(app).apply { showAll(content()) }
        val dm=app.resources.displayMetrics;val w=dm.widthPixels-2*CallUi.dp(app,12)
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        v.layout(0,0,v.measuredWidth,v.measuredHeight)
        val bmp=Bitmap.createBitmap(v.measuredWidth,v.measuredHeight.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        val c=Canvas(bmp);c.drawColor(CallUi.PAGE);v.draw(c)
        java.io.File(out!!).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
        return v.measuredHeight
    }

    @Test fun fourCardPictures() {
        assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!out.isNullOrBlank())
        val h10=shoot("card_light_10",false,1.0f);shoot("card_dark_10",true,1.0f)
        val h13=shoot("card_light_13",false,1.3f);shoot("card_dark_13",true,1.3f)
        assertTrue("larger letters make a taller card ($h13 vs $h10)",h13>h10)
    }
}
