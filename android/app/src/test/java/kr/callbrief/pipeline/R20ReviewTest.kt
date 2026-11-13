package kr.callbrief.pipeline

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kr.callbrief.GeneralLocalStore
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v3.9 r20 independent review: M1 an item marked [틀렸어요] is not made again (the same call analysed again, the same job in other
 * words) nor opened by a result, and the morning tidy sees it with its title; M2 it can be taken back from "끝낸 일 보기" and is
 * marked on its call's page; L1 the new-install test; L5 the two buttons stack when they do not fit. Made-up calls only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R20ReviewTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val number="010-2222-3333"
    private val t0=System.currentTimeMillis()-3*3_600_000L
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }

    @Before fun setup() {
        AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2","rec3").forEach { id ->
                p.execute("INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",
                    id,GeneralLocalStore.OWNER,id,"$id.wav")
            }
            null } }
    }
    @After fun reset() { CallCardAsk.updatedHook=null;TaskActions.resetIo();IeumApplication.shutdown();ContactCards.clearPending();CallUi.applyTheme(app) }
    private fun store()=IeumApplication.store(app)
    private fun task(op:String,title:String,id:String?=null)=ClaudeCallAnalysis.Task(op,id,title,null,"견적서는 내일까지 메일로 보내드릴게요")
    /** One call of [number] at [at] with its result ([tasks]). Returns the record id. */
    private fun call(recording:String,at:Long,vararg tasks:ClaudeCallAnalysis.Task):String {
        val t=CallTranscript(recording,1,"a".repeat(64),at,120_000,number,"통화 내용",listOf(CallTranscript.Segment(0,5000,"통화 내용")),"m","b".repeat(64),true)
        store().insert(t);val id=CallRecordStore.recordId(t)
        store().applyAnalysis(id,analysis(*tasks))
        return id
    }
    private fun analysis(vararg tasks:ClaudeCallAnalysis.Task)=ClaudeCallAnalysis("견적서를 내일까지 보내기로 했어요.",emptyList(),"김민수","탄탄이엔씨",emptyList(),tasks.toList(),
        counterpart=ClaudeCallAnalysis.Counterpart("김민수","탄탄이엔씨","대표",false))
    private fun status(id:String)=store().cardRows("SELECT status FROM workflow_items WHERE id=?",id).single()["status"] as String
    private fun openTitles()=store().openItems().map { it.title }

    @Test fun aWrongItemIsNotMadeAgainNorOpenedAgain() {
        val first=call("rec1",t0,task("create","철근 견적서 보내기"))
        val item=store().openItems().single { it.title=="철근 견적서 보내기" }
        store().markWrong(item.id,item.revision)
        assertFalse(openTitles().contains("철근 견적서 보내기"))
        // The same call's result applied again (a retry), and a later call with the same job, also in other words: nothing new.
        store().applyAnalysis(first,analysis(task("create","철근 견적서 보내기")))
        call("rec2",t0+30*60_000,task("create","철근 견적서 보내기"),task("create","철근 견적서를 보내기"))
        assertTrue(openTitles().toString(),openTitles().none { it.contains("견적서") })
        // A result that names it (update, done_suggested) does not open it again.
        call("rec3",t0+60*60_000,task("update","철근 견적서 보내기 (수정본)",item.id),task("done_suggested","",item.id))
        assertEquals(CallRecordStore.WRONG,status(item.id))
        assertTrue(openTitles().none { it.contains("견적서") })
        // The morning tidy's state: the wrong item with its title and company, like the tasks.
        val w=store().tidyState(true,System.currentTimeMillis()).getJSONArray("wrong").getJSONObject(0)
        assertEquals(item.id,w.getString("id"));assertEquals("철근 견적서 보내기",w.getString("title"));assertEquals("탄탄이엔씨",w.getString("company"))
        assertTrue(w.getLong("wrongMs")>0 && w.has("createdMs"))
    }

    @Test fun aWrongItemIsTakenBackFromTheFinishedListAndMarkedOnItsCall() {
        val rec=call("rec1",t0,task("create","철근 견적서 보내기"))
        val item=store().openItems().single()
        store().markWrong(item.id,item.revision)
        assertTrue(store().doneItems(0).any { it.id==item.id && it.status==CallRecordStore.WRONG })
        IeumApplication.shutdown()
        val a=launchMain(Screen.TASKS)
        settle { tag(a,"tasks_done")!=null || views(a.window.decorView).any { (it.tag as? String)=="task_add" } }
        // Open the folded "끝낸 일 보기": the item is there, marked, with its own [되돌리기].
        val fold=views(a.window.decorView).filterIsInstance<TextView>().single { it.text.toString().startsWith("끝낸 일 보기") }
        fold.performClick()
        settle { tag(a,"done_${item.id}")!=null }
        val row=tag(a,"done_${item.id}")
        assertEquals(TaskActions.WRONG_MARK,(views(row).single { it.tag=="done_wrong_mark" } as TextView).text.toString())
        views(row).single { it.tag=="done_wrong_undo" }.performClick()
        settle { runCatching { status(item.id) }.getOrNull()==item.status }
        settle { TaskFeedback.heartbeat(SecureSettings(app).snapshot()).getLong("undone")==1L }
        assertTrue(store().openItems().any { it.id==item.id })
        // Marked again: its call's page says so under the item.
        val again=store().openItems().single { it.id==item.id }
        store().markWrong(again.id,again.revision)
        IeumApplication.shutdown()
        val c=launchMain(Screen.CALLS,dayMs=t0,callId=rec)
        settle { tag(c,"call_task_${item.id}")!=null }
        assertEquals(TaskActions.WRONG_MARK,(views(tag(c,"call_task_${item.id}")).single { it.tag=="task_wrong_mark" } as TextView).text.toString())
    }

    @Test fun theNewInstallTestLeavesUpdatedPhonesAndUnreadSettingsAlone() {
        val ui=app.getSharedPreferences("ieum_ui",android.content.Context.MODE_PRIVATE)
        ui.edit().clear().commit()
        // Settings not read: not decided, nothing kept, asked again next time.
        assertFalse(CallCardAsk.cardRequired(app,null) { false })
        assertFalse(ui.contains(CallCardAsk.CARD_REQUIRED))
        // The store cannot be read: not decided either.
        assertFalse(CallCardAsk.cardRequired(app,JSONObject()) { error("store") })
        assertFalse(ui.contains(CallCardAsk.CARD_REQUIRED))
        // An app updated over an older one, or a store with calls: not a new install, and that is kept.
        CallCardAsk.updatedHook={ true }
        assertFalse(CallCardAsk.cardRequired(app,JSONObject()) { false })
        assertFalse(ui.getBoolean(CallCardAsk.CARD_REQUIRED,true))
        ui.edit().clear().commit();CallCardAsk.updatedHook={ false }
        assertFalse(CallCardAsk.cardRequired(app,JSONObject()) { true })
        ui.edit().clear().commit()
        assertTrue(CallCardAsk.cardRequired(app,JSONObject()) { false })
        assertTrue(ui.getBoolean(CallCardAsk.CARD_REQUIRED,false))
    }

    @Test fun theTwoButtonsStackWhenTheyDoNotFit() {
        val a=launchMain(Screen.HOME)
        val pair=CallUi.taskButtons(a,"철근 견적서 보내기",View(a),{},{}) as CallUi.ButtonPair
        fun lay(w:Int) { pair.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)) }
        val free=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        val need=(0 until pair.childCount).map(pair::getChildAt).sumOf { it.measure(free,free);it.measuredWidth }+CallUi.dp(a,8)
        lay(need+10);assertTrue(pair.sideBySide);assertEquals(android.widget.LinearLayout.HORIZONTAL,pair.orientation)
        // Narrower than both together (200% letters on a small phone): one under the other, at the end, neither squeezed.
        lay(need-1);assertFalse(pair.sideBySide);assertEquals(android.widget.LinearLayout.VERTICAL,pair.orientation)
        (0 until pair.childCount).map(pair::getChildAt).forEach { b -> assertTrue(b.measuredWidth<=need-1) }
    }
}
