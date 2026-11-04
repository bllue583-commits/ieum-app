package kr.callbrief.pipeline

import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowDialog

/** v2.7 r8-5: the check rows (only a call's item opens its call, G3 order, the name in META), the company name fix, and settings
 * that keep their place and what is typed, save sliders after a pause and count no empty recording as a failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CheckCompanySettingsTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun tagged(a:android.app.Activity,prefix:String)=views(a.window.decorView).filter { (it.tag as? String)?.startsWith(prefix)==true }
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val app get()=RuntimeEnvironment.getApplication()
    private fun title(a:MainActivity)=(tag(a,"page_title") as? TextView)?.text?.toString()
    private fun sheet():Dialog=ShadowDialog.getLatestDialog().also { assertTrue(it.isShowing) }
    private fun Dialog.find(t:String):View=window!!.decorView.findViewWithTag<View>(t) ?: error("no $t in the sheet")
    private fun lay(a:MainActivity) { val d=a.window.decorView;d.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));d.layout(0,0,1080,1920) }
    private fun pause(ms:Long) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms)) }
    @After fun reset() { CallUi.applyTheme(app);TaskActions.resetIo() }

    // ---- check rows ----

    @Test fun onlyACallsItemOpensItsCallAndTheNameInMetaOpensIt() {
        PreviewSeed.ensure(app)
        val own=CallRecordStore(app).use { it.addUserItem("직접 적은 일 하나") }!!
        val items=CallRecordStore(app).use { it.openItems() }
        assertFalse("a user item has no call",items.single { it.id==own }.fromCall)
        assertEquals("user:items",items.single { it.id==own }.recordingId)
        val call=items.first { it.fromCall && !it.counterpart.isNullOrBlank() }
        val a=launchMain(Screen.TASKS)
        settle { tag(a,"task_$own")!=null && tag(a,"task_${call.id}")!=null }
        // The user's own item shows no press at all, and its META has no link.
        val mine=tag(a,"task_$own")
        assertFalse(mine.isClickable);assertNull(mine.background)
        assertTrue(views(mine).none { it.tag=="task_who_link" })
        mine.performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Screen.TASKS,a.screen);assertNull(a.detail)
        // G3 order in a call's row: badge, title, META, (note), [다 됐어요].
        val row=tag(a,"task_${call.id}")
        assertTrue(row.isClickable)
        val order=views(row).map { it.tag }
        val at=listOf("task_title","task_meta","task_done").map { order.indexOf(it) }
        assertTrue(order.toString(),at.all { it>=0 } && at==at.sorted())
        if(call.company!=null) assertTrue(order.indexOf("task_company")<order.indexOf("task_title"))
        val link=views(row).single { it.tag=="task_who_link" } as TextView
        assertTrue(link.text.toString(),link.text.toString().startsWith(CallUi.who(call.counterpart!!)))
        assertTrue(views(row).filterIsInstance<TextView>().any { it.tag=="task_who" && it.text.toString().endsWith("요청") })
        // The name opens that call (one Activity: the calls screen with its detail).
        link.performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Screen.CALLS,a.screen);assertEquals(call.recordId,a.detail)
    }

    @Test fun manyOpenItemsAreDrawnInPartsAndAllArrive() {
        PreviewSeed.ensure(app)
        CallRecordStore(app).use { s -> repeat(75) { s.addUserItem("많은 일 $it") } }
        val ids=CallRecordStore(app).use { s -> s.openItems().filter { CallRecordStore.kstDay(it.createdMs)>=CallUi.today().minusDays(14) }.map { "task_"+it.id }.toSet() }
        assertTrue(ids.size>30)
        val a=launchMain(Screen.TASKS)
        settle { tag(a,"task_add")!=null }
        // Every row arrives, a part at a time, and each once.
        settle { tagged(a,"task_").count { it.tag in ids }==ids.size }
        assertEquals(ids.size,tagged(a,"task_").map { it.tag }.filter { it in ids }.toSet().size)
        assertEquals(30,a.tasks.FIRST_ROWS)
    }

    // ---- company name fix ----

    @Test fun aCompanyNameIsFixedInTheSheetAndTheOldNameStaysAnAlias() {
        PreviewSeed.ensure(app)
        val before=CallRecordStore(app).use { it.companies() }.first()
        val a=launchMain(Screen.COMPANIES)
        settle { tag(a,"company_${before.id}")!=null }
        tag(a,"company_${before.id}").performClick()
        settle { tag(a,"company_rename")!=null }
        assertEquals("업체 이름 고치기",(tag(a,"company_rename") as TextView).text.toString())
        tag(a,"company_rename").performClick()
        val d=sheet()
        assertEquals("업체 이름 고치기",(d.find("sheet_title") as TextView).text.toString())
        val input=d.find("company_name_input") as EditText
        assertEquals(before.name,input.text.toString())
        // An empty name keeps the sheet open with a word about it.
        input.setText("  ");d.find("sheet_primary").performClick()
        assertTrue(d.isShowing);assertEquals(View.VISIBLE,d.find("sheet_problem").visibility)
        pause(700)
        input.setText("새이름건설");d.find("sheet_primary").performClick()
        assertFalse(d.isShowing)
        settle { title(a)=="새이름건설" }
        assertEquals("업체 이름을 고쳤어요",org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        val companies=CallRecordStore(app).use { it.companies() }
        assertTrue(companies.any { it.id==before.id && it.name=="새이름건설" });assertTrue(companies.none { it.name==before.name })
        val aliases=AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p ->
            p.query("SELECT company_id FROM company_aliases WHERE company_id=?",before.id).size }
        assertTrue("the old spelling is kept",aliases>=1)
        // Back: the list held from before is drawn at once (no wait for the store), then read again with the new name.
        a.onBackPressedDispatcher.onBackPressed()
        assertEquals("업체와 사람",title(a));assertNull(a.detail)
        settle { texts(a).any { it.text.toString()=="새이름건설" } }
    }

    @Test fun renameCompanyKeepsTheOldNameFindableAndJoinsATakenName() {
        PreviewSeed.ensure(app)
        CallRecordStore(app).use { s ->
            val list=s.companies();assertTrue(list.size>=2)
            val (x,y)=list[0] to list[1]
            assertNull(s.renameCompany(x.id,"   "));assertNull(s.renameCompany("co_none","이름"))
            assertEquals(x.id,s.renameCompany(x.id,x.name))
            assertEquals(x.id,s.renameCompany(x.id,"바뀐 이름 상사"))
            // A user item for the old spelling lands on the renamed company (found through the alias).
            val item=s.addUserItem("옛 이름으로 적은 일",x.name)!!
            assertEquals("바뀐 이름 상사",s.openItems().single { it.id==item }.company)
            // The name of another company: the two become one, that one stays.
            assertEquals(y.id,s.renameCompany(x.id,y.name))
            assertTrue(s.companies().none { it.id==x.id })
            assertEquals(y.name,s.openItems().single { it.id==item }.company)
        }
    }

    // ---- settings ----

    @Test fun settingsKeepTheirPlaceAndWhatIsTypedWhenDrawnAgain() {
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"mail_user")!=null && tag(a,"report_problem")!=null }
        (tag(a,"mail_user") as EditText).setText("kim@example.com")
        (tag(a,"mail_password") as EditText).setText("app-pass")
        // A service button changes the guide and the server field in place; the fields are the same views with the same words.
        val user=tag(a,"mail_user")
        tag(a,"mail_service_2").performClick()
        assertSame(user,tag(a,"mail_user"));assertEquals("kim@example.com",(tag(a,"mail_user") as EditText).text.toString())
        assertTrue((tag(a,"mail_guide") as TextView).text.toString().startsWith("Gmail"))
        assertEquals(View.GONE,tag(a,"mail_host").visibility)
        tag(a,"mail_service_3").performClick();assertEquals(View.VISIBLE,tag(a,"mail_host").visibility)
        (tag(a,"mail_host") as EditText).setText("imap.example.com")
        // Scrolled down, then drawn again (as when coming back from the mail app): same place, same words.
        val scroll=tag(a,"page_scroll") as android.widget.ScrollView
        lay(a);scroll.scrollTo(0,1200);lay(a);val y=scroll.scrollY;assertTrue("page scrolls",y>0)
        a.settings.enter()
        settle { tag(a,"mail_user")!=null && tag(a,"mail_user")!==user }
        settle { lay(a);scroll.scrollY==y }
        assertEquals("kim@example.com",(tag(a,"mail_user") as EditText).text.toString())
        assertEquals("app-pass",(tag(a,"mail_password") as EditText).text.toString())
        assertEquals("imap.example.com",(tag(a,"mail_host") as EditText).text.toString())
        assertEquals(View.VISIBLE,tag(a,"mail_host").visibility)
    }

    @Test fun aSliderIsSavedAfterAPauseWithoutDrawingThePageAgain() {
        PreviewSeed.ensure(app)
        val key=PipelineSettingsKeys.EVENING_HOUR
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"hour_bar_$key")!=null && tag(a,"report_problem")!=null }
        val bar=tag(a,"hour_bar_$key") as SeekBar
        val listener=shadowOf(bar).onSeekBarChangeListener
        val start=SecureSettings(app).snapshot().optInt(key,19)
        // Three steps, as TalkBack or a drag gives them: only the words change until the pause is over.
        listOf(20,21,22).forEach { bar.progress=it;listener.onProgressChanged(bar,it,true) }
        assertEquals("오후 10시",(tag(a,"hour_$key") as TextView).text.toString())
        pause(300)
        assertEquals(start,SecureSettings(app).snapshot().optInt(key,19))
        pause(500)
        settle { SecureSettings(app).snapshot().optInt(key,19)==22 }
        settle { tag(a,"settings_status")!=null }
        assertSame("the slider was not drawn again",bar,tag(a,"hour_bar_$key"))
        // A switch: its words change and it stays the same view.
        val consent=tag(a,"consent_switch") as android.widget.Switch
        consent.isChecked=false
        assertEquals("꺼짐: 정리하지 않아요",consent.text.toString())
        settle { !SecureSettings(app).snapshot().optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,true) }
        // The lights follow (consent off is amber); the switch is the same view.
        settle { views(tag(a,"status_analyze")).filterIsInstance<TextView>().any { it.text.toString().contains("정리 동의가 꺼져 있어요") } }
        assertSame(consent,tag(a,"consent_switch"))
    }

    @Test fun anEmptyRecordingIsNoFailureAndALightGoesToItsGroup() {
        CallRecordStore(app).use { it.counts() } // the tables exist
        val now=System.currentTimeMillis()
        AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT INTO call_records(record_id,recording_id,duration_ms,transcript,segments_json,state,error,created_ms) VALUES('r_empty','rec_empty',60000,'','[]','FAILED','EMPTY_TRANSCRIPT',?)",now)
            p.execute("INSERT INTO call_records(record_id,recording_id,duration_ms,transcript,segments_json,state,error,created_ms) VALUES('r_bad','rec_bad',60000,'말','[]','FAILED','OTHER',?)",now);null } }
        assertEquals(1,StatusLights.read(app,JSONObject(),now).failed)
        SecureSettings(app).update { it.put("treeUri","content://preview/tree").put(PipelineSettingsKeys.CONSENT_GRANTED,true) }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"handled_calls")!=null }
        assertTrue((tag(a,"handled_calls") as TextView).text.toString().endsWith("정리 못 한 통화 1건"))
        assertNotNull(tag(a,"handled_empty"))
        val analyze=views(tag(a,"status_analyze")).filterIsInstance<TextView>().joinToString { it.text }
        assertTrue(analyze,analyze.contains("정리하지 못한 통화가 1건"))
        // That light names the group below it: pressing it scrolls there.
        val scroll=tag(a,"page_scroll") as android.widget.ScrollView
        lay(a);scroll.scrollTo(0,0);lay(a)
        val light=tag(a,"status_analyze");assertTrue(light.isClickable)
        light.performClick();lay(a)
        val group=tag(a,StatusLights.GROUP_STATUS)
        assertTrue("scrolled toward ${StatusLights.GROUP_STATUS}",scroll.scrollY>0 && scroll.scrollY<=group.top+group.height)
        // An action button goes busy on its own; the others stay usable.
        val report=tag(a,"report_problem") as TextView
        assertTrue(report.isEnabled)
        assertTrue((tag(a,"folder_pick") as TextView).isEnabled)
    }
}
