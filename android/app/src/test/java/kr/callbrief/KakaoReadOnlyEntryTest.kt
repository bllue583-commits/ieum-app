package kr.callbrief
import android.content.*
import android.os.Looper
import android.view.*
import android.widget.TextView
import kr.callbrief.kakao.android.KakaoConfiguration
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class KakaoReadOnlyEntryTest {
    companion object {var active=true;var reads=0;var block=false;var entered=CountDownLatch(1);var release=CountDownLatch(1);val key="0".repeat(32)}
    internal class Host:KakaoDigestEntryActivity(){
        override fun configuration()=KakaoConfiguration(key,12345,"https://example.org/ieum",true,false)
        override fun ownerReady()=true
        override fun ownerStillCurrent()=active
        override suspend fun storedReports(date:LocalDate)=withContext(Dispatchers.IO){reads++;if(block){entered.countDown();check(release.await(5,TimeUnit.SECONDS))};listOf("합성 현재 계정" to "SYNTHETIC_READ_ONLY_REPORT")}
    }
    @Before fun setup(){active=true;reads=0;block=false;entered=CountDownLatch(1);release=CountDownLatch(1)}
    @After fun finish(){release.countDown()}
    private fun intent(query:String)=Intent(RuntimeEnvironment.getApplication(),Host::class.java).setData(android.net.Uri.parse("kakao$key://kakaolink?$query"))
    private fun texts(v:View):List<String> =(if(v is TextView)listOf(v.text.toString())else emptyList())+if(v is ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))}else emptyList()
    private fun waitUi(a:android.app.Activity){repeat(150){Shadows.shadowOf(Looper.getMainLooper()).idle();if(a.isFinishing || texts(a.findViewById(android.R.id.content)).any{it.contains("SYNTHETIC_READ_ONLY_REPORT")})return;Thread.sleep(10)}}
    @Test fun linkDisplaysOnlyStoredReportWithoutStartingAnyAction(){val c=Robolectric.buildActivity(Host::class.java,intent("screen=digest&date=2026-10-13").putExtra("send",true).putExtra("ownerId","PUBLIC_FORGED_OWNER")).setup();waitUi(c.get());assertEquals(1,reads);assertTrue(texts(c.get().findViewById(android.R.id.content)).contains("SYNTHETIC_READ_ONLY_REPORT"));assertNull(Shadows.shadowOf(c.get()).nextStartedActivity);c.pause().stop().destroy()}
    @Test fun extraSendQueryAndDuplicateDateAreRejectedBeforeReading(){for(q in listOf("screen=digest&date=2026-10-13&send=true","screen=digest&date=2026-10-13&date=2026-10-14")){val c=Robolectric.buildActivity(Host::class.java,intent(q)).setup();assertTrue(c.get().isFinishing);c.pause().stop().destroy()};assertEquals(0,reads)}
    @Test fun lateReportCannotPublishAfterOwnerChange(){block=true;val c=Robolectric.buildActivity(Host::class.java,intent("screen=digest&date=2026-10-13")).setup();assertTrue(entered.await(5,TimeUnit.SECONDS));active=false;release.countDown();repeat(80){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};assertTrue(c.get().isFinishing);assertFalse(texts(c.get().findViewById(android.R.id.content)).contains("SYNTHETIC_READ_ONLY_REPORT"));c.pause().stop().destroy()}
    @Test fun defaultBuildDisablesKakaoCallbacksAndLiveSender(){val context=RuntimeEnvironment.getApplication();assertFalse(BuildConfig.DAILY_REPORT_KAKAO_LIVE_ENABLED);assertFalse(KakaoConfiguration.fromResources(context).canLogin);val info=context.packageManager.getActivityInfo(ComponentName(context,KakaoDigestEntryActivity::class.java),android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);assertFalse(info.enabled)}
}
