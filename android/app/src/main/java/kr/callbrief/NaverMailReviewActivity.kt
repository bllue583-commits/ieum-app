package kr.callbrief

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Preserved mailbox candidates are reviewed without opening a new mailbox/network session. */
internal open class NaverMailReviewActivity:ComponentActivity() {
    private lateinit var root:LinearLayout
    private lateinit var session:GoogleConnectionSession
    private lateinit var context:Context
    private var owner="";private var epoch=-1L;private var generation=0L;private var closed=false;private var busy=false
    private var task:Job?=null;private var dialog:AlertDialog?=null;private var message=""
    private var candidates=JSONArray();private var recordings=JSONArray()
    protected open fun createSession()=GoogleConnectionSession.get(this)
    private fun current()=!closed&&!isFinishing&&!isDestroyed&&::session.isInitialized&&session.epoch()==epoch&&session.ownerBinding()==owner
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24);setOnApplyWindowInsetsListener { v,i->v.setPadding(24,i.systemWindowInsetTop+24,24,i.systemWindowInsetBottom+24);i } };setContentView(root)
        try { session=createSession();val v=session.dailyReportScope();owner=v.ownerId;epoch=v.epoch;context=if(owner.isBlank())this else AccountStorageContext(this,owner);load() }
        catch(_:Exception) { root.addView(text("현재 계정의 메일 후보를 확인하지 못했어요. 자료는 지우지 않았어요.")) }
    }
    private fun text(value:String)=TextView(this).apply { text=value;textSize=18f;setPadding(0,12,0,12);isSaveEnabled=false }
    private fun button(value:String,action:()->Unit)=Button(this).apply { text=value;textSize=18f;isAllCaps=false;minHeight=(56*resources.displayMetrics.density).toInt();isEnabled=!busy;setOnClickListener { if(current()&&!busy)action() } }
    private fun list(a:JSONArray)=(0 until a.length()).map { a.getJSONObject(it) }
    private fun render() {
        if(!current())return;root.removeAllViews();root.addView(text("네이버 메일 후보 검토 · 읽기 전용"));root.addView(button("목록으로 돌아가기"){finish()})
        val scroll=ScrollView(this);val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        body.addView(text("기기에 수신한 후보만 보여줘요. 메일을 새로 읽거나 원본을 바꾸지 않아요. 이름·이메일만으로 업무를 자동 연결하지 않아요."))
        if(message.isNotBlank())body.addView(text(message));if(busy)body.addView(text("저장 후보 확인 중"))
        if(candidates.length()==0&&!busy)body.addView(text("수신한 메일 후보가 없어요. 미연결·미수집 상태를 관련 메일 없음으로 단정하지 않아요."))
        list(candidates).forEach { c ->val p=JSONObject(c.getString("payload_json"));body.addView(text(c.getString("subject")));body.addView(text("${p.getString("origin")} · ${p.getString("bodyState")} · 업무 연결 확인 필요"))
            body.addView(button("원문·근거 확인") {
                dialog?.dismiss();dialog=AlertDialog.Builder(this).setTitle("메일 후보 원문").setView(ScrollView(this).apply { addView(text(p.toString(2))) }).setPositiveButton("닫기",null).create();ProtectedDialogWindow.show(dialog!!)
            })
            if(!c.isNull("raw_text")&&recordings.length()>0) {
                val selected=Spinner(this).apply { adapter=ArrayAdapter(this@NaverMailReviewActivity,android.R.layout.simple_spinner_dropdown_item,list(recordings).map { r->listOf(r.optString("counterpart_label").takeUnless { it.isBlank()||it=="null" } ?: "상대 확인 필요",r.optString("case_label").takeUnless { it.isBlank()||it=="null" } ?: "업무 확인 필요",r.optString("original_date").takeUnless { it=="null" } ?: "날짜 확인 필요").joinToString(" · ") }) };body.addView(selected)
                body.addView(button("선택 통화와 관련성 직접 확인") {
                    val chosen=recordings.getJSONObject(selected.selectedItemPosition)
                    dialog=AlertDialog.Builder(this).setTitle("메일 연결 확인").setMessage("원문과 선택한 통화의 사람·업무를 확인한 후 근거로 연결해요. 메일의 제안은 상태·완료를 자동 변경하지 않아요.")
                        .setNegativeButton("취소",null).setPositiveButton("근거로 연결") { _,_->save(chosen,c.getString("digest")) }.create();ProtectedDialogWindow.show(dialog!!)
                })
            }
        }
        body.addView(button("메일 연결 설정") { ScopedNaverMailSettingsActivity.open(this) })
        body.addView(button("후보 다시 읽기") { load() })
    }
    private fun load()=operation { RecordingWorkflowStore(context).use { it.naverCandidates() } to GeneralLocalStore.catalog(context).listFiles() }
    private fun save(expected:JSONObject,digest:String)=operation("선택 메일을 근거로 저장했어요. 분석 포함은 별도 동의예요.") {
        RecordingWorkflowStore(context).use { it.confirmNaverCandidate(expected.getString("file_id"),expected,digest) };RecordingWorkflowStore(context).use { it.naverCandidates() } to GeneralLocalStore.catalog(context).listFiles()
    }
    private fun operation(messageAfter:String?=null,action:()->Pair<JSONArray,JSONArray>) {
        if(!current()||busy)return;busy=true;val ticket=++generation;render()
        task=lifecycleScope.launch { try { val result=withContext(Dispatchers.IO){action()};ensureActive();if(current()&&generation==ticket){candidates=result.first;recordings=result.second;messageAfter?.let { message=it }} }
            catch(e:CancellationException){throw e}catch(_:Exception){if(current()&&generation==ticket)message="후보 읽기·저장을 확인하지 못했어요. 기존 통화·완료·메일 원문은 유지해요."}
            finally {if(generation==ticket){busy=false;if(current())render()}} }
    }
    override fun onResume(){super.onResume();if(::session.isInitialized){if(!current()){dialog?.dismiss();finish()}else if(!busy)load()}}
    override fun onStop(){generation++;task?.cancel();busy=false;dialog?.dismiss();super.onStop()}
    override fun onDestroy(){closed=true;generation++;task?.cancel();dialog?.dismiss();super.onDestroy()}
}
