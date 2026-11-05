package kr.callbrief.pipeline

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import kr.callbrief.GeneralLocalStore
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
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
import java.time.LocalDate

/** v3.3 r13-3 (DESIGN_V4 3.3): undo of a merge of people, the settings switches and lines, "확인 필요" and [다 됐어요] on a call's
 * detail page, the transcript's [접기], the company search kept, and "지금 할 일:" in the morning report. Made-up rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class V33ScreensTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun tagged(a:android.app.Activity,prefix:String)=views(a.window.decorView).filter { (it.tag as? String)?.startsWith(prefix)==true }
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private fun port()=AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql"))
    @After fun reset() { SettingsScreen.goldenHook=null;IeumApplication.shutdown();CallUi.applyTheme(app) }

    // ---- 3.3-1 합친 사람 되돌리기 ----
    private fun person(p:AndroidSqlPort,id:String,name:String,phone:String?,lid:String?=null,updated:Long=1) {
        p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key,ledger_id) VALUES(?,?,?,NULL,NULL,0,?,?,?)",id,name,phone,updated,NameKey.canon(name),lid)
        if(phone!=null) p.execute("INSERT OR IGNORE INTO people_phones VALUES(?,?)",phone,id)
    }
    private fun links(id:String)=port().use { it.query("SELECT COUNT(*) AS n FROM call_links WHERE person_id=?",id).single()["n"] as Number }.toInt()
    private fun edits()=port().use { it.query("SELECT json FROM ledger_edits ORDER BY created_ms").map { r -> JSONObject(r["json"] as String) } }
    /** Two spellings of one number, the same name: the migration makes them one and logs the merged person's rows. */
    private fun mergedPair() {
        CallRecordStore(app).use { }
        port().use { p -> p.transaction {
            person(p,"a1","김민수","01011110000","L_a1",updated=9);person(p,"a2","김민수","821011110000","L_a2")
            // A second number only the merged one has.
            p.execute("INSERT OR IGNORE INTO people_phones VALUES('01099990000','a2')")
            p.execute("INSERT INTO call_links VALUES('call_a1','a1',NULL)")
            p.execute("INSERT INTO call_links VALUES('call_a2','a2',NULL)");p.execute("INSERT INTO call_links VALUES('call_a2b','a2',NULL)")
            p.execute("INSERT INTO person_aliases VALUES('김민쑤','a2')")
            p.execute("UPDATE app_migration SET version=3");null } }
        IeumApplication.shutdown()
        CallRecordStore(app).use { }
    }
    private fun log()=SecureSettings(app).snapshot().optString(PipelineSettingsKeys.MERGE_LOG,"[]")

    @Test fun aMergeUndoneGivesBackTheCallsNumbersAndSpellingAndTheLedgerHearsNothing() {
        mergedPair()
        assertEquals(0,links("a2"));assertEquals(3,links("a1"))
        // The merge was sent to the ledger (both had ledger ids).
        assertEquals(listOf("mergePeople:L_a2>L_a1"),edits().map { it.getString("type")+":"+it.getString("from")+">"+it.getString("into") })
        val entry=CallRecordStore(app).use { s -> s.mergeEntries(log()).single() }
        assertEquals("김민수",entry.name);assertEquals("김민수",entry.intoName);assertEquals(2,entry.links)
        assertTrue(CallRecordStore(app).use { it.undoMerge(entry) })
        // The counts are as before the merge.
        assertEquals(2,links("a2"));assertEquals(1,links("a1"))
        port().use { p ->
            assertEquals("L_a2",p.query("SELECT ledger_id FROM people WHERE id='a2'").single()["ledger_id"])
            assertEquals("L_a1",p.query("SELECT ledger_id FROM people WHERE id='a1'").single()["ledger_id"])
            assertEquals("a2",p.query("SELECT person_id FROM person_aliases WHERE name_key='김민쑤'").single()["person_id"])
            // The number only the merged one had goes back to it alone.
            assertEquals(listOf("a2"),p.query("SELECT person_id FROM people_phones WHERE phone_key='01099990000' AND person_id IN ('a1','a2')").map { it["person_id"] })
            assertEquals(1,p.query("SELECT * FROM people WHERE id='a2'").size)
        }
        // Not sent yet: the merge edit is dropped, no undo edit goes; the log no longer lists it; a second undo does nothing.
        assertTrue(edits().toString(),edits().isEmpty())
        assertTrue(CallRecordStore(app).use { it.mergeEntries(log()) }.isEmpty())
        assertFalse(CallRecordStore(app).use { it.undoMerge(entry) })
    }

    @Test fun aMergeAlreadySentIsAnsweredByAnUndoEdit() {
        mergedPair()
        port().use { it.execute("DELETE FROM ledger_edits") } // the hourly work sent it
        val entry=CallRecordStore(app).use { s -> s.mergeEntries(log()).single() }
        assertTrue(CallRecordStore(app).use { it.undoMerge(entry) })
        assertEquals(listOf("unmergePeople:L_a2>L_a1"),edits().map { it.getString("type")+":"+it.getString("from")+">"+it.getString("into") })
    }

    @Test fun settingsOffersTheUndoOnlyWhileThereIsAMerge() {
        val none=launchMain(Screen.SETTINGS)
        settle { texts(none).any { it.text.toString()=="설정" } && tag(none,"app_version")!=null }
        assertNull(tag(none,SettingsScreen.MERGE_TAG))
        IeumApplication.shutdown()
        mergedPair()
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,SettingsScreen.MERGE_TAG)!=null }
        assertTrue(texts(a).any { it.text.toString().startsWith("이음이 같은 사람으로 보고 합친 사람이 1명") })
    }

    // ---- 3.3-4 and the r13-1 hand-over: settings ----
    @Test fun settingsCardSwitchesSayWhatTheyDoAndTheLockScreenOneIsOnAtFirst() {
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"card_unknown_switch")!=null }
        assertEquals("아직 정리된 통화가 없는 번호도",(tag(a,"card_unknown_switch") as Switch).text.toString())
        val lock=tag(a,"lock_screen_detail_switch") as Switch
        assertEquals("잠금 화면에서도 이름과 한 줄 보기",lock.text.toString());assertTrue(lock.isChecked)
        lock.performClick()
        settle { SecureSettings(app).snapshot().has(PipelineSettingsKeys.LOCK_SCREEN_DETAIL) }
        assertFalse(SecureSettings(app).snapshot().getBoolean(PipelineSettingsKeys.LOCK_SCREEN_DETAIL))
        // No golden screen in this build: no line.
        assertNull(tag(a,"golden_link"))
    }

    @Test fun theGoldenLineShowsOnlyWhenTheScreenIsInTheBuild() {
        assertFalse(SettingsScreen.goldenInBuild())
        SettingsScreen.goldenHook={ true }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"golden_link")!=null }
    }

    @Test fun anAmberLightPutsTheStatusFirst() {
        // A fresh phone: no folder, no consent, so at least one light is amber.
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"settings_status")!=null }
        val groups=tagged(a,"group_").map { it.tag as String }
        assertEquals(groups.toString(),"group_${SettingsScreen.STATUS_NAME}",groups.first())
        // The lights are in that group, not again under 상태와 도움.
        assertEquals(1,tagged(a,"settings_status").size)
        assertSame(tag(a,"group_${SettingsScreen.STATUS_NAME}"),tag(a,"settings_status").parent)
    }

    // ---- 3.3-4 and r13-1: the call's detail page ----
    @Test fun aCallsChecksShowTheBadgeAndCanBeFinishedAndTheTranscriptFolds() {
        PreviewSeed.ensure(app)
        val call=CallRecordStore(app).use { s -> s.day(CallUi.today()).first { it.analysis?.counterpartCompany=="△△철강" && it.applied.any { a -> a.applied } } }
        val item=call.applied.first { it.applied }.itemId
        IeumApplication.shutdown()
        port().use { it.execute("UPDATE workflow_items SET tentative=1 WHERE id=?",item) }
        val a=launchMain(Screen.CALLS,call.dayMs,call.recordId)
        settle { tag(a,"call_task_$item")!=null }
        val row=tag(a,"call_task_$item") as ViewGroup
        assertEquals(CallUi.UNSURE,(views(row).first { it.tag=="task_unsure" } as TextView).text.toString())
        assertNotNull("open: [다 됐어요] here too",views(row).firstOrNull { it.tag=="task_done" })
        val toggle=tag(a,"toggle_transcript") as TextView
        val shut=toggle.text.toString()
        toggle.performClick();assertEquals(CallsScreen.TRANSCRIPT_CLOSE,toggle.text.toString());assertTrue(tag(a,"call_transcript").isShown)
        toggle.performClick();assertEquals(shut,toggle.text.toString());assertEquals(View.GONE,tag(a,"call_transcript").visibility)
    }

    @Test fun theCompanySearchStaysAfterAPageIsClosed() {
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.COMPANIES)
        settle { tag(a,"companies_search")!=null && tagged(a,"company_").any { it is ViewGroup } }
        (tag(a,"companies_search") as EditText).setText("철강")
        settle { tagged(a,"company_").filter { it is ViewGroup }.size==1 }
        tagged(a,"company_").first { it is ViewGroup }.performClick()
        settle { texts(a).any { it.text.toString().startsWith("사람 (") } }
        a.onBackPressedDispatcher.onBackPressed()
        settle { (tag(a,"companies_search") as? EditText)?.text?.toString()=="철강" }
    }

    // ---- 3.3-4: the morning report ----
    @Test fun theMorningReportLeadsWithTheItemItsButtonFinishesAndPutsIssuesBelow() {
        val t=LocalDate.of(2026,11,6).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
        val items=List(9) { CallTodoItem("i$it","일 $it","","open",1,null,t+it,null,false) }
        val m=CallDigestText.morning(items,LocalDate.of(2026,11,6),listOf(Issue("철근 단가","회신 대기",emptyList())))
        assertEquals("i0",m.doneId);assertTrue(m.text,m.text.startsWith(CallDigestText.NOW_PREFIX+"일 0, "))
        assertTrue(m.lines.first(),m.lines.first().startsWith("지금 할 일: 일 0"))
        assertEquals("진행 중, 철근 단가: 회신 대기",m.lines.last());assertTrue(m.lines.size<=CallDigestText.MAX_LINES)
        assertTrue(m.lines.any { it.startsWith("외 ") })
        assertEquals(1,m.lines.count { it.startsWith(CallDigestText.NOW_PREFIX) })
    }

    @Test fun anOldLogEntryWithoutTheKeptNumbersStillUndoes() {
        mergedPair()
        // A log written before r13-3 has no "intoPhones": the merged one's rows still come back, the kept one keeps its numbers.
        val old=JSONArray(log()).getJSONObject(0).apply { remove("intoPhones") }
        SecureSettings(app).update { it.put(PipelineSettingsKeys.MERGE_LOG,JSONArray().put(old).toString()) }
        val entry=CallRecordStore(app).use { s -> s.mergeEntries(log()).single() }
        assertTrue(CallRecordStore(app).use { it.undoMerge(entry) })
        assertEquals(2,links("a2"));assertEquals(1,links("a1"))
    }
}
