package kr.callbrief.navermail.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class NaverMailSettingsActivityTest {
    private fun activity()=Robolectric.buildActivity(NaverMailSettingsActivity::class.java,Intent().putExtra(NaverMailSettingsActivity.EXTRA_OWNER,"fixture-${UUID.randomUUID()}")).setup().get().also{shadowOf(android.os.Looper.getMainLooper()).idle()}
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    @Test fun defaultUiShowsNotConnectedWithNoSecretInput(){val a=activity();val all=views(a.window.decorView);assertTrue(all.filterIsInstance<TextView>().any{it.text.toString().contains("미연결")});assertFalse(all.any{it is EditText});assertFalse((all.single{it.tag=="naver_connect"}as Button).isEnabled);assertFalse(BuildConfig.REAL_ACCOUNT_ACTIONS_APPROVED);assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)}
    @Test fun guideIsCaptureProtectedAndExplicitAboutSeparateAuthorization(){val a=activity();views(a.window.decorView).filterIsInstance<Button>().single{it.text=="실제 인증에 필요한 단계 보기"}.performClick();val f=NaverMailSettingsActivity::class.java.getDeclaredField("dialog").apply{isAccessible=true};val d=f.get(a) as android.app.AlertDialog;assertTrue(d.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0);assertTrue(views(d.window!!.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("새 연결이 승인되는")});assertTrue(views(d.window!!.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("네이버 로그인은").not()&&it.text.toString().contains("전용 앱 비밀번호")})}
    @Test fun missingHostOwnerFailsClosed(){val a=Robolectric.buildActivity(NaverMailSettingsActivity::class.java).setup().get();assertTrue(views(a.window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("사용자 식별")});assertFalse((views(a.window.decorView).single{it.tag=="naver_connect"}as Button).isEnabled)}
}
