package kr.callbrief.pipeline

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.report.MorningBoard
import kr.callbrief.pipeline.report.ReportFetch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v4.4 r25-2: the morning tidy's board on home, under the to-dos, in the web view's order; an empty block not drawn, a long
 * one folded in place; no report today, nothing new on home. Every name and number is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R25BoardTest:ProtectedTextHostTestBase() {
    private val app get()=org.robolectric.RuntimeEnvironment.getApplication()
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private fun home(s:HomeScreen.Snapshot):MainActivity {
        val a=launchMain(Screen.HOME);settle { tag(a,"home_today")!=null || tag(a,"retry_card")!=null };a.home.render(s);shadowOf(Looper.getMainLooper()).idle();return a
    }
    private val today get()=CallUi.today()
    private fun report(day:java.time.LocalDate=today)=JSONObject().put("day",day.toString())
        .put("calls",JSONArray((1..4).map { JSONObject().put("who","박하늘 과장").put("company","가나건설").put("what","거푸집 반입 시간 $it").put("since","어제") }))
        .put("waiting",JSONArray(listOf(JSONObject().put("who","최서준 대리").put("company","다라전기").put("what","배선 견적서").put("since","11/3").put("days",12),
            JSONObject().put("who","이도윤").put("what","수정 도면").put("since","어제").put("days",1).put("unsure",true))))
        .put("week",JSONArray((0..3).map { d -> JSONObject().put("date",day.plusDays(d.toLong()).toString()).put("items",JSONArray(listOf("일정 $d"))) }))
        .put("inProgress",JSONArray(listOf(JSONObject().put("what","3층 슬래브 타설").put("status","레미콘 회신 대기").put("days",4))))
        .put("changed",JSONArray(listOf("철근 반입 날짜 바뀜")))
    private fun snap(board:MorningBoard.Board?)=HomeScreen.Snapshot(true,true,"READY",0,0,0,0,0,0,board=board)

    @Test fun theBoardComesUnderTheToDosInTheWebOrder() {
        val board=MorningBoard.read(report(),today)
        val a=home(snap(board))
        val order=views(a.window.decorView).mapNotNull { it.tag as? String }.filter { it.startsWith(HomeBoard.TAG_PREFIX) && it.endsWith("_head") }
        assertEquals(listOf("calls","waiting","week","progress","changed").map { HomeBoard.TAG_PREFIX+it+"_head" },order)
        val all=views(a.window.decorView)
        assertTrue("after 먼저 볼 체크할 일",all.indexOf(tag(a,"home_tasks_head"))<all.indexOf(tag(a,HomeBoard.TAG_PREFIX+"calls_head")))
        assertTrue("before the numbers",all.indexOf(tag(a,HomeBoard.TAG_PREFIX+"changed_head"))<all.indexOf(tag(a,"home_today")))
        // No 확인 필요 or 끝났는지 확인할 일 in this report: not drawn.
        assertNull(tag(a,HomeBoard.TAG_PREFIX+"check_head"));assertNull(tag(a,HomeBoard.TAG_PREFIX+"done_head"))
        // 전화할 곳: three rows, the fourth folded; it opens in place.
        val calls=tag(a,HomeBoard.TAG_PREFIX+"calls") as ViewGroup
        assertEquals(3,views(calls).count { it.tag=="board_main" })
        val more=tag(a,HomeBoard.TAG_PREFIX+"calls_more") as TextView
        assertEquals("외 1개 더 보기 ›",more.text.toString());assertEquals(View.GONE,tag(a,HomeBoard.TAG_PREFIX+"calls_more_rows").visibility)
        more.performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE,tag(a,HomeBoard.TAG_PREFIX+"calls_more_rows").visibility);assertEquals("접기",more.text.toString())
        // A new reading keeps it open.
        a.home.render(snap(board));shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE,tag(a,HomeBoard.TAG_PREFIX+"calls_more_rows").visibility)
        // 답을 기다리는 중: "n일째" on the right, amber after 7 days; the doubt as a mark, not as words in the line.
        val days=views(tag(a,HomeBoard.TAG_PREFIX+"waiting")).filterIsInstance<TextView>().filter { it.tag=="board_days" }
        assertEquals(listOf("12일째","1일째"),days.map { it.text.toString() })
        assertEquals(CallUi.AMBER,days[0].currentTextColor);assertEquals(CallUi.SUBTLE,days[1].currentTextColor)
        assertEquals(1,views(tag(a,HomeBoard.TAG_PREFIX+"waiting")).count { it.tag=="board_unsure" })
        val sub=views(tag(a,HomeBoard.TAG_PREFIX+"waiting")).filterIsInstance<TextView>().first { it.tag=="board_sub" }
        assertEquals("배선 견적서, 11/3 약속",sub.text.toString())
        // 이번 주 일정: today and tomorrow, the other days behind one line.
        assertNotNull(tag(a,"board_day_$today"));assertNotNull(tag(a,"board_day_${today.plusDays(1)}"))
        assertEquals("다른 날 2일 더 보기 ›",(tag(a,HomeBoard.TAG_PREFIX+"week_more") as TextView).text.toString())
        // The screen rules: reading size (META 16 marked), no middle dot.
        val texts=views(a.window.decorView).filterIsInstance<TextView>()
        assertTrue(texts.none { it.text.contains('·') || it.text.contains('…') })
        assertTrue(texts.filter { it !is android.widget.Button }.all { it.textSize/it.resources.displayMetrics.scaledDensity>=((it.getTag(kr.callbrief.R.id.ui_min_sp) as? Float) ?: 18f)-0.01f })
    }
    @Test fun noReportTodayDrawsNothingNew() {
        val a=home(snap(null))
        assertTrue(views(a.window.decorView).none { (it.tag as? String)?.startsWith(HomeBoard.TAG_PREFIX)==true })
        // The report kept in the settings: yesterday's is not today's, a v11 report has no board, a broken text none.
        val s=JSONObject().put(ReportFetch.REPORT_BODY,report(today.minusDays(1)).toString())
        assertNull(HomeScreen.board(s,today))
        assertNull(HomeScreen.board(JSONObject().put(ReportFetch.REPORT_BODY,JSONObject().put("day",today.toString()).put("inProgress",JSONArray()).toString()),today))
        assertNull(HomeScreen.board(JSONObject().put(ReportFetch.REPORT_BODY,"{broken"),today))
        assertEquals(4,HomeScreen.board(JSONObject().put(ReportFetch.REPORT_BODY,report().toString()),today)!!.calls.size)
    }

    // ---- v4.4 r25-3: old to-dos do not pile up in front of the person (nothing is closed, the store stays as it is) ----
    @Test fun onlyRecentAndReportedToDosStayOpenTheRestFold() {
        val day=86_400_000L;val now=System.currentTimeMillis()
        fun item(id:String,at:Long,status:String="open",callMs:Long?=null)=OpenItem(id,id,"",status,1,"",null,createdMs=at,callMs=callMs)
        val all=listOf(item("new",now),item("six",now-6*day),item("eight",now-8*day),item("old_check",now-20*day,"needs_confirmation"),
            item("old_reported",now-30*day),item("old_talked",now-30*day,callMs=now-2*day),item("unknown",0))
        val (open,past)=TasksScreen.split(all,listOf("old_reported"),today)
        assertEquals(setOf("new","six","old_reported","old_talked"),open.map { it.id }.toSet())
        assertEquals("newest first",listOf("eight","old_check","unknown"),past.map { it.id })
        assertEquals(listOf("b","a"),TasksScreen.reportIds(JSONObject().put("today",JSONArray(listOf(JSONObject().put("id","b"),JSONObject().put("id","a"),JSONObject().put("id","b"),JSONObject())))))
        assertTrue(TasksScreen.reportIds(null).isEmpty())
        val a=launchMain(Screen.TASKS)
        val groups=a.tasks.groups(open,listOf("old_reported","missing","six"))
        assertEquals(TasksScreen.REPORT_GROUP,groups[0].first);assertEquals("in the report's order",listOf("old_reported","six"),groups[0].second.map { it.id })
        assertTrue(groups.drop(1).none { g -> g.second.any { it.id=="old_reported" || it.id=="six" } })
    }
    @Test fun theTaskListFoldsWhatIsOlderThanAWeek() {
        PreviewSeed.ensure(app)
        val before=CallRecordStore(app).use { it.openItems().size }
        val old=System.currentTimeMillis()-9*86_400_000L
        val oldIds=(1..3).map { i -> CallRecordStore(app) { old }.use { it.addUserItem("지난주 일 $i") }!! }
        val newId=CallRecordStore(app).use { it.addUserItem("오늘 적은 일") }!!
        val a=launchMain(Screen.TASKS)
        settle { tag(a,"task_add")!=null && tag(a,"task_$newId")!=null }
        assertTrue(tag(a,"task_$newId").isShown)
        oldIds.forEach { assertNull(tag(a,"task_$it")?.takeIf { v -> v.isShown }) }
        val fold=tag(a,"tasks_old") as TextView
        assertTrue(fold.text.toString(),Regex("지난 일 \\d+개 보기 ›").matches(fold.text.toString()))
        fold.performClick();shadowOf(Looper.getMainLooper()).idle()
        oldIds.forEach { assertTrue(tag(a,"task_$it").isShown) }
        assertEquals("nothing closed",before+4,CallRecordStore(app).use { it.openItems().size })
    }
}
