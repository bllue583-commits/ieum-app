package kr.callbrief.pipeline

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
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

/** v3.9 r20-2 (DESIGN_V7 6): [틀렸어요] beside [다 됐어요]: one tap, the item leaves the list as "wrong" (not done), undo brings it
 * back, only counts go to the heartbeat (`feedback{wrong, undone}`) and only ids to the morning tidy's state (`wrong[]`).
 * The preview's made-up rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R20TwoTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val app get()=RuntimeEnvironment.getApplication()
    private fun pause(ms:Long) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms)) }
    @After fun reset() { CallUi.applyTheme(app);TaskActions.resetIo();kr.callbrief.IeumApplication.shutdown() }
    private fun status(id:String)=CallRecordStore(app).use { it.cardRows("SELECT status FROM workflow_items WHERE id=?",id).single()["status"] as String }
    private fun feedback()=TaskFeedback.heartbeat(SecureSettings(app).snapshot())

    @Test fun wrongTakesTheItemOutUndoBringsItBackAndOnlyCountsGoUp() {
        PreviewSeed.ensure(app)
        val own=CallRecordStore(app).use { it.addUserItem("직접 적은 일 하나") }!!
        val items=CallRecordStore(app).use { it.openItems() }
        val ai=items.filter { it.fromCall }.take(2);assertEquals(2,ai.size)
        val a=launchMain(Screen.TASKS)
        settle { ai.all { tag(a,"task_${it.id}")!=null } && tag(a,"task_$own")!=null }
        // The user's own item has no [틀렸어요]; an AI item has it before [다 됐어요], 48dp tall at least.
        assertTrue(views(tag(a,"task_$own")).none { it.tag=="task_wrong" })
        val row=tag(a,"task_${ai[0].id}")
        val order=views(row).map { it.tag }
        assertTrue(order.toString(),order.indexOf("task_wrong") in 0 until order.indexOf("task_done"))
        val wrong=views(row).single { it.tag=="task_wrong" } as TextView
        assertEquals(CallUi.WRONG_LABEL,wrong.text.toString())
        assertTrue(wrong.minHeight>=(48*wrong.resources.displayMetrics.density).toInt())
        assertEquals(0,feedback().getLong("wrong"));assertEquals(0,feedback().getLong("undone"))

        // One tap: the bar says it, the store has it as wrong (not done), the count goes up, the row goes when the bar does.
        wrong.performClick()
        settle { tag(a,"snack_undo")!=null && status(ai[0].id)==CallRecordStore.WRONG }
        assertTrue(views(tag(a,"snack_bar")).filterIsInstance<TextView>().any { it.text.toString()==TaskActions.WRONG_WORDS })
        settle { feedback().getLong("wrong")==1L }
        assertFalse(CallRecordStore(app).use { it.openItems() }.any { it.id==ai[0].id })
        // r20 review M2: it shows in "끝낸 일 보기" as a wrong one (to be taken back from there), not as a finished one.
        assertTrue(CallRecordStore(app).use { it.doneItems(0) }.any { it.id==ai[0].id && it.status==CallRecordStore.WRONG })
        pause(5_500)
        settle { tag(a,"task_${ai[0].id}")==null }

        // The second one, taken back with [되돌리기]: open again with its old status, and undone counts once.
        pause(700)
        val second=views(tag(a,"task_${ai[1].id}")).single { it.tag=="task_wrong" }
        second.performClick()
        settle { tag(a,"snack_undo")!=null && status(ai[1].id)==CallRecordStore.WRONG }
        tag(a,"snack_undo").performClick()
        settle { status(ai[1].id)==ai[1].status && feedback().getLong("undone")==1L }
        // Both presses count as pressed; the one taken back counts once more as undone (wrong - undone = kept wrong).
        assertEquals(2L,feedback().getLong("wrong"))
        assertTrue(CallRecordStore(app).use { it.openItems() }.any { it.id==ai[1].id })

        // The morning tidy's state: the wrong item with its title (r20 review M1: like the tasks), and not among its tasks.
        val state=CallRecordStore(app).use { it.tidyState(true,System.currentTimeMillis()) }
        val ids=state.getJSONArray("wrong").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("id") } }
        assertEquals(ai[0].title,state.getJSONArray("wrong").getJSONObject(0).getString("title"))
        assertEquals(listOf(ai[0].id),ids)
        val tasks=state.getJSONArray("tasks").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("id") } }
        assertFalse(ai[0].id in tasks);assertTrue(ai[1].id in tasks)
        // The heartbeat carries the counts, numbers only.
        val hb=PhoneStatus.build(PhoneStatus.Input(System.currentTimeMillis(),"4.0",1L,0,null,0L,0L,"READY","","",1,feedback=feedback()))
        assertEquals(JSONObject().put("wrong",2).put("undone",1).toString(),hb.getJSONObject("feedback").toString())
    }

    @Test fun theStoreRefusesAStaleWrongAndAnalysisLeavesAWrongItemAlone() {
        PreviewSeed.ensure(app)
        val item=CallRecordStore(app).use { it.openItems() }.first { it.fromCall }
        CallRecordStore(app).use { s ->
            assertTrue(runCatching { s.markWrong(item.id,item.revision+5) }.isFailure)
            s.markWrong(item.id,item.revision)
            assertTrue("twice",runCatching { s.markWrong(item.id,item.revision+1) }.isFailure)
            assertEquals(listOf(item.id),s.wrongIds(0))
            assertFalse(s.openTaskIds().contains(item.id))
            // Kept apart from done: the history says the user touched it, so a later call's result only notes on it.
            assertEquals(1L,(s.cardRows("SELECT COUNT(*) AS n FROM workflow_item_history WHERE item_id=?",item.id).single()["n"] as Number).toLong())
            s.undoWrong(item.id,item.status,item.revision+1)
            assertEquals(item.status,s.cardRows("SELECT status FROM workflow_items WHERE id=?",item.id).single()["status"])
            assertTrue(s.wrongIds(0).isEmpty())
        }
        // No wrong item: the state has no wrong[] at all (an old state reads the same).
        assertFalse(CallRecordStore(app).use { it.tidyState(true,System.currentTimeMillis()) }.has("wrong"))
    }
}
