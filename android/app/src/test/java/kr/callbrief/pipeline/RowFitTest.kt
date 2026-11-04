package kr.callbrief.pipeline

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Rows never squeeze text into a column of single letters (device-test 11/4: "김민수 님, 오늘 요청" one letter per line next to
 * [다 됐어요], and the three home labels running into each other). Measured with real text widths on a 320dp phone and on a
 * Galaxy S25 (412dp), with letters at 1.0 and 1.3 times. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowFitTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private val cases=listOf(320 to 1.0f,320 to 1.3f,412 to 1.0f,412 to 1.3f)

    private fun activity(widthDp:Int,scale:Float):Activity {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h800dp-port")
        RuntimeEnvironment.setFontScale(scale)
        return Robolectric.buildActivity(Activity::class.java).setup().get()
    }
    /** Lays [v] out as it sits on a screen: page sides 20dp, inside the list sheet nothing more (the row has its own padding). */
    private fun lay(a:Activity,v:View,widthDp:Int,sidesDp:Int=40):Int {
        val w=CallUi.dp(a,widthDp-sidesDp)
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        v.layout(0,0,v.measuredWidth,v.measuredHeight);return w
    }
    private fun text(root:View,tag:String)=views(root).first { it.tag==tag } as TextView
    /** A text never gets a box narrower than about four letters of its own size. */
    private fun notSqueezed(root:View,label:String) = views(root).filterIsInstance<TextView>().filter { it.visibility==View.VISIBLE && it.text.isNotEmpty() }.forEach {
        val four=it.paint.measureText("가나다라").toInt()
        assertTrue("$label: '${it.text}' squeezed to ${it.width}px (${it.layout?.lineCount} lines)",it.width>=minOf(four,it.paint.measureText(it.text.toString()).toInt()))
    }

    @Test fun taskRowKeepsItsWordsOnOneOrTwoLines() = cases.forEach { (w,scale) ->
        val a=activity(w,scale);val label="${w}dp x$scale"
        val row=CallUi.taskRow(a,"김민수 님께 견적서 보내기",System.currentTimeMillis(),"△△철강","김민수",onDone={},onOpen={})
        lay(a,row,w)
        notSqueezed(row,label)
        assertTrue(label,text(row,"task_who").layout.lineCount<=2)
        assertTrue(label,text(row,"task_title").layout.lineCount<=2)
        val done=text(row,"task_done");assertEquals(label,1,done.layout.lineCount)
        assertTrue(label,done.width>=done.paint.measureText("다 됐어요").toInt())
        // A long company name and the company screen's row (no badge, no button) hold too.
        val long=CallUi.taskRow(a,"현장 철근 반입 일정 다시 맞추기",System.currentTimeMillis()-86_400_000L,"(주)대한종합건설산업","박영호",status="in_progress",onDone={},aiNote="지난주에 한 번 미뤄졌어요.")
        lay(a,long,w);notSqueezed(long,"$label long");assertTrue(label,text(long,"task_who").layout.lineCount<=2)
        val plain=CallUi.taskRow(a,"견적서 보내기",System.currentTimeMillis(),null,"김민수")
        lay(a,plain,w);notSqueezed(plain,"$label plain");assertEquals(label,1,text(plain,"task_who").layout.lineCount)
    }

    @Test fun homeNumberLabelsNeverRunIntoEachOther() = cases.forEach { (w,scale) ->
        val a=activity(w,scale);val label="${w}dp x$scale"
        val stats=CallUi.stats(a,listOf(Triple("1","오늘 통화",CallUi.INK),Triple("1","새 체크",CallUi.INK),Triple("12","체크할 일",CallUi.ACCENT)),listOf("건","개","개"))
        val card=CallUi.card(a).apply { addView(stats) }
        lay(a,card,w)
        val labels=(0..2).map { text(stats,"stat_label_$it") }
        labels.forEach { assertEquals("$label '${it.text}'",1,it.layout.lineCount);assertTrue("$label '${it.text}'",it.width>=it.paint.measureText(it.text.toString()).toInt()) }
        // No two labels overlap on the screen.
        fun box(v:View):android.graphics.Rect { var x=0;var y=0;var p:View?=v;while(p!=null && p!==card) { x+=p.left;y+=p.top;p=p.parent as? View }
            return android.graphics.Rect(x,y,x+(v as TextView).paint.measureText(v.text.toString()).toInt(),y+v.height) }
        val boxes=labels.map(::box)
        for(i in boxes.indices) for(j in i+1 until boxes.size) assertFalse("$label ${labels[i].text}/${labels[j].text} $boxes",android.graphics.Rect.intersects(boxes[i],boxes[j]))
        // On the S25 at normal size the three numbers stay side by side.
        if(w==412 && scale==1.0f) assertTrue((stats as CallUi.Stats).columns)
    }

    @Test fun callAndStatusRowsKeepTheirWords() = cases.forEach { (w,scale) ->
        val a=activity(w,scale);val label="${w}dp x$scale"
        val memo=CallUi.memoRow(a,"오후 12:05","△△철강 김민수","김민수 님과 견적서 일정을 이야기했어요.") {}
        lay(a,CallUi.list(a,listOf(memo)),w)
        notSqueezed(memo,label);assertEquals(label,1,text(memo,"memo_time").layout.lineCount)
        val number=CallUi.memoRow(a,"오후 4:30","010-3344-5566","정리하고 있어요") {}
        lay(a,CallUi.list(a,listOf(number)),w);assertEquals("$label phone number",1,text(number,"memo_who").layout.lineCount)
        val light=CallUi.signalRow(a,"마지막으로 결과를 받은 때","11월 4일 (수) 오후 12:05",true)
        lay(a,CallUi.card(a).apply { addView(light) },w);notSqueezed(light,label)
    }
}
