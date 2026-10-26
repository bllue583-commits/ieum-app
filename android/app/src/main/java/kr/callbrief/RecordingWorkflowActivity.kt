package kr.callbrief

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Normal per-recording user workflow, not a second demo database. All sources are data. */
internal open class RecordingWorkflowActivity:ComponentActivity() {
    private lateinit var root:LinearLayout
    private lateinit var session:GoogleConnectionSession
    private lateinit var scoped:Context
    private var owner="";private var epoch=-1L;private var id=""
    private var data:JSONObject?=null;private var cases=JSONArray()
    private var busy=false;private var closed=false;private var generation=0L;private var operation:Job?=null
    private var message="";private var dialog:AlertDialog?=null
    @Volatile private var analysis:AnalysisRequestFlow?=null
    private var pendingExport:String?=null
    private var requestedTodo:String?=null
    private var todoRouteConsumed=false
    protected open fun createStore()=RecordingWorkflowStore(scoped,scopeCurrent={current()},analysisActor={session.nativeSessionBinding()})
    protected open fun createAnalysis()=AnalysisRequestFlow(this,session)
    protected open fun createSession()=GoogleConnectionSession.get(this)
    private fun alive()=!closed&&!isFinishing&&!isDestroyed
    private fun current()=alive()&&::session.isInitialized&&session.epoch()==epoch&&session.ownerBinding()==owner
    private fun items(a:JSONArray?)=a?.let { (0 until it.length()).map { n->it.getJSONObject(n) } } ?: emptyList()
    private val exportPicker=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val value=pendingExport;pendingExport=null
        if(uri==null||value==null||!current()) { if(current()){message="등록 근거 저장을 취소했어요. 요청과 결과 이력은 유지해요.";render()};return@registerForActivityResult }
        perform { contentResolver.openOutputStream(uri,"w")?.use { it.write(value.toByteArray(Charsets.UTF_8)) } ?: error("EXPORT_NOT_CONFIRMED")
            "앱의 등록 근거를 선택 파일에 저장했어요. dot 인계·분석 실행 성공을 뜻하지 않아요." }
    }
    private val textPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri==null) { if(current()){message="선택을 취소했어요. 기존 관련 자료는 유지해요.";render()};return@registerForActivityResult }
        if(!current())return@registerForActivityResult
        perform {
            val raw=contentResolver.openInputStream(uri)?.use { input ->
                val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(4096)
                while(true) { val n=input.read(bytes);if(n<0)break;check(out.size()+n<=64000) { "RELATED_FILE_TOO_LARGE" };out.write(bytes,0,n) }
                val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
                check(decoded.length<=20000) { "RELATED_FILE_TOO_LARGE" };decoded
            } ?: error("RELATED_FILE_UNAVAILABLE")
            withContext(Dispatchers.Main) { if(current())relatedForm("MAIL",raw) };"파일 원문을 확인해 주세요. 아직 연결하지 않았어요."
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(221,241,248))
            setOnApplyWindowInsetsListener { v,i->v.setPadding(dp(16),i.systemWindowInsetTop+dp(12),dp(16),i.systemWindowInsetBottom+dp(12));i } }
        setContentView(root);id=intent.getStringExtra("recording_id").orEmpty()
        requestedTodo=intent.getStringExtra("edit_todo_id")?.takeIf { it.isNotBlank() }
        todoRouteConsumed=savedInstanceState?.getBoolean("todoRouteConsumed",false) ?: false
        try { session=createSession();val v=session.dailyReportScope();owner=v.ownerId;epoch=v.epoch;scoped=if(owner.isBlank())this else AccountStorageContext(this,owner)
            check(id.isNotBlank());reload()
        } catch(_:Exception) { root.addView(label("현재 계정과 통화를 확인하지 못했어요. 기존 자료는 유지해요."));root.addView(button("뒤로"){finish()}) }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun label(t:String,bold:Boolean=false)=TextView(this).apply { text=t;textSize=if(bold)22f else 18f;setTextColor(Color.rgb(13,34,112));setPadding(0,dp(6),0,dp(6));if(bold)setTypeface(typeface,Typeface.BOLD);isSaveEnabled=false }
    private fun button(t:String,action:()->Unit)=Button(this).apply { text=t;textSize=18f;isAllCaps=false;minHeight=dp(56);isEnabled=!busy;setOnClickListener { if(current()&&!busy)action() } }
    private fun block(parent:LinearLayout,title:String)=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));addView(label(title,true));parent.addView(this) }
    private fun field(parent:LinearLayout,title:String,value:String="",multi:Boolean=false)=EditText(this).apply {
        hint=title;textSize=18f;setText(value);isSaveEnabled=false;isSaveFromParentEnabled=false;importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or if(multi)InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        minHeight=dp(56);if(multi)minLines=3;parent.addView(label(title));parent.addView(this)
    }
    private fun render() {
        if(!current()||!::root.isInitialized)return
        root.removeAllViews();root.addView(label("통화의 분석·업무·관련 자료",true));root.addView(button("통화 목록으로 돌아가기"){finish()})
        val scroll=ScrollView(this);val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        if(message.isNotBlank())body.addView(label(message).apply { tag="workflow_message" })
        if(busy)body.addView(label("확인 중이에요. 완료나 전송 성공으로 표시하지 않았어요."))
        val r=data ?: return
        val work=block(body,"사람·업무 확인")
        work.addView(label(listOf("person_label","company_label","work_label").map { r.optString(it).takeUnless { s->s.isBlank()||s=="null" } ?: "확인 필요" }.joinToString(" · ")))
        work.addView(label(if(r.optBoolean("phone_confirmed"))"직접 확인 번호 · 끝자리 ${r.optString("phone_ending")}" else "전화번호 확인 필요 · 같은 번호만으로 업무를 합치지 않아요."))
        work.addView(button("사람·회사·업무 직접 연결") { workForm() }.apply { tag="workflow_confirm_work" })
        work.addView(label("통화 날짜·시각은 통화 상세의 직접 확인 버튼으로 저장해 주세요. 수집 시각을 대신 쓰지 않아요."))
        val analysisBox=block(body,"분석 요청과 결과")
        analysisBox.addView(button("이 통화의 분석 요청 준비") { perform { createAnalysis().use { it.prepare(id,checkNotNull(data)) };"분석 요청을 기기에 준비했어요. 녹음은 전달하지 않았어요." } }.apply { tag="workflow_prepare_analysis" })
        items(r.optJSONArray("requests")).forEach { request ->
            val requestId=request.getString("id");val state=request.getString("state")
            analysisBox.addView(label(AnalysisRequestFlow.text(state)))
            AnalysisRequestFlow.reasonText(request.optString("reason"))?.let { analysisBox.addView(label(it)) }
            if(state in listOf("UNKNOWN","HELD_SOURCE","HELD_AUTH") && request.optBoolean("has_reserved_binding"))analysisBox.addView(button("원래 작업 확인 후 같은 요청 게시 재개") {
                confirm("원래 분석 요청 게시 재개","저장된 원래 작업·파일·계정·원음·수동 버전을 다시 확인해요. 일치할 때만 같은 작업과 파일로 게시를 재개하며 새 작업을 만들지 않아요. 자동 dot 실행을 뜻하지 않아요.","같은 요청 확인·재개") {
                    perform { createAnalysis().use { flow->analysis=flow;AnalysisRequestFlow.text(flow.resume(requestId)) } }
                }
            }.apply { tag="workflow_resume_original_request" })
            if(state in listOf("PREPARED","HELD_AUTH","HELD_SOURCE"))analysisBox.addView(button("승인된 합성 원음만 Drive에 전달") {
                confirm("분석 요청 전달","개인 통화는 전달하지 않아요. 기존 합성 원음 승인·Google 계정·Drive 동의와 파일/버전이 모두 맞을 때만 전달해요. 관련 문자·메일은 별도로 분석 포함에 동의한 원문만 포함해요. dot 실행 완료를 뜻하지 않아요.","확인하고 전달") {
                    perform { createAnalysis().use { flow->analysis=flow;AnalysisRequestFlow.text(flow.submit(requestId)) } }
                }
            }.apply { tag="workflow_submit_analysis" })
            if(!request.isNull("job_id") && state!="CANCELLED")analysisBox.addView(button("이 요청의 결과 회수") {
                perform { createAnalysis().use { flow->analysis=flow;AnalysisRequestFlow.text(flow.poll(requestId)) } }
            })
            if(!request.isNull("job_id") && state!="CANCELLED") {
                analysisBox.addView(button("게시 요청·결과·dot 인계 상태 확인") { perform { createAnalysis().use { flow->analysis=flow;AnalysisRequestFlow.text(flow.sync(requestId)) } } })
                if(BuildConfig.SYNTHETIC_TOOLS_ENABLED)analysisBox.addView(button("Drive A · 이 합성 한 건의 인계 준비") {
                    startActivity(Intent(this,DriveAForegroundActivity::class.java).putExtra("local_request_id",requestId))
                }.apply {tag="workflow_drive_a_candidate"})
                analysisBox.addView(button("분석 요청 인계 자료 저장") { confirm("분석 요청 인계 자료","dot 실행 연결을 확인할 자료를 파일로 저장해요. 원음·로그인 토큰은 포함하지 않으며, 관련 원문은 별도로 분석 포함에 동의한 것만 들어가요. 파일 저장만으로 dot가 실행되지는 않아요.","인계 자료 준비") {
                    perform { val exported=createAnalysis().use { flow->analysis=flow;flow.export(requestId) };withContext(Dispatchers.Main){if(current()){pendingExport=exported;exportPicker.launch("ieum_native_request_registration.json")}};"등록 근거의 저장 위치를 선택해 주세요." }
                } })
            }
            if(state=="ERROR")analysisBox.addView(button("실패 이력을 보존하고 새 시도") { confirm("명시 재시도","외부 오류가 확인된 요청만 새로 시도해요. 전달 여부가 불명확한 요청은 자동 재전송하지 않아요.","새로 시도") { perform { createAnalysis().use { flow->analysis=flow;AnalysisRequestFlow.text(flow.retry(requestId)) } } } })
            if(state!="CANCELLED")analysisBox.addView(button("이 기기의 후속 처리 중단") { perform { createStore().use { it.transitionRequest(requestId,"CANCELLED",reason="LOCAL_STOP_REMOTE_COPIES_NOT_RECALLED") };AnalysisRequestFlow.text("CANCELLED") } })
        }
        analysisBox.addView(button("Google 계정·Drive 연결 확인") { startActivity(Intent(this,ConnectionCenterActivity::class.java)) })
        val proposals=block(body,"원문·분석 제안 전체 이력")
        val p=items(r.optJSONArray("proposals"))
        if(p.isEmpty())proposals.addView(label("이 통화에 수신한 결과가 없어요. 분석 완료로 표시하지 않아요."))
        p.forEach { result ->
            proposals.addView(label("파일 ${result.optLong("source_version")} · 결과 ${result.optLong("result_revision")} · 읽기 전용"))
            proposals.addView(label(result.optString("summary_text").takeUnless { it=="null" } ?: "요약 미확인"))
            proposals.addView(button("이 결과의 원문·근거 보기") { textDialog("전사·분석 제안",result.optString("raw_text")+"\n\n"+result.optString("analysis_json")+"\n\n출처·의미·사람/업무 연결은 별도 확인 필요 · 자동 적용 없음") })
            if(result.optInt("schema_version")==0)proposals.addView(button("분석 항목을 업무에 반영할지 검토") {
                perform {
                    val review=createStore().use { it.businessReview(id,result.getString("digest")) }
                    withContext(Dispatchers.Main) { if(current())businessForm(review) }
                    "분석 제안과 원문 근거를 열었어요. 선택·확인 전 업무 기록은 그대로예요."
                }
            }.apply { tag="workflow_review_business" })
            if(result.optLong("source_version")==r.getLong("source_version") && result.optInt("hidden")==0)
            {
                proposals.addView(button("읽고 직접 확인한 요약 작성") { summaryForm(result) })
                proposals.addView(button("이 원문을 근거로 필요 서류 작성") { itemForm(null,"DOCUMENT",proposal=result) })
                proposals.addView(button("이 원문을 근거로 todo 작성") { itemForm(null,"TODO",proposal=result) })
            }
        }
        val reviewed=block(body,"내가 직접 확인한 요약")
        items(r.optJSONArray("reviewed_history")).forEach { old ->reviewed.addView(button("파일 ${old.optLong("source_version")} · 직접 확인 ${old.optLong("revision")}차") { textDialog("직접 확인 요약 이력",old.getString("text")) }) }
        reviewed.addView(button("내 확인 요약 새로 저장") { summaryForm(null) }.apply { tag="workflow_review_summary" })
        reviewed.addView(label("직접 메모·완료는 유지해요. 새 파일·수동 변경 후 보고에는 다시 확인이 필요해요."))
        val checklist=block(body,"필요 서류와 todo")
        checklist.addView(button("필요 서류 직접 추가") { itemForm(null,"DOCUMENT") }.apply { tag="workflow_add_document" })
        checklist.addView(button("todo 직접 추가") { itemForm(null,"TODO") }.apply { tag="workflow_add_todo" })
        items(r.optJSONArray("items")).forEach { item ->
            checklist.addView(label((if(item.getString("kind")=="DOCUMENT")"필요 서류" else "todo")+": "+item.getString("title")+" · "+item.getString("status")))
            if(item.optString("edition").isNotBlank())checklist.addView(label("문서 판: ${item.getString("edition")}"))
            if(item.optString("due_text").isNotBlank())checklist.addView(label("직접 입력 기한: ${item.getString("due_text")} · 시각·시간대 추정 없음"))
            checklist.addView(button("내용·상태 수정") { itemForm(item,item.getString("kind")) })
        }
        val history=block(body,"업무 진행 이력")
        history.addView(label("직접 확인한 진행 상태만 저장해요. 서류 하나의 완료를 전체 업무 완료로 바꾸지 않아요."))
        if(!r.isNull("task_id"))history.addView(button("업무 진행 상태 직접 수정") { progressForm() }.apply { tag="workflow_edit_progress" })
        items(r.optJSONArray("progress_history")).forEach { event ->history.addView(label("${event.optLong("task_revision")}차 · ${event.optString("before_status")} → ${event.optString("after_status")}")) }
        val related=block(body,"관련 문자·메일")
        related.addView(button("연결된 문자·메일의 읽기 후보") { startActivity(Intent(this,RelatedReadOnlyActivity::class.java).putExtra("recording_id",id)) }.apply { tag="workflow_read_related_sources" })
        related.addView(button("동일 번호 문자 추가 · 직접 선택") { relatedForm("SMS") }.apply { tag="workflow_add_sms" })
        related.addView(button("선택 메일 텍스트 추가") { relatedForm("MAIL") })
        related.addView(button("메일 텍스트 파일 선택") { textPicker.launch(arrayOf("text/plain","message/rfc822")) })
        related.addView(button("네이버 메일 선택 연결") { val i=Intent(this,ScopedNaverMailSettingsActivity::class.java).putExtra("workflow_recording_id",id);startActivity(i) })
        items(r.optJSONArray("naver_candidates")).forEach { candidate ->
            related.addView(label("네이버 관련 메일 후보: ${candidate.getString("subject")} · 업무 확정 아님"))
            val payload=JSONObject(candidate.getString("payload_json"));related.addView(label("본문 상태: ${payload.getString("bodyState")} · ${payload.getString("origin")}"))
            related.addView(button("메일 후보 원문·근거 보기") { textDialog("네이버 메일 후보",payload.toString(2)) })
            if(!candidate.isNull("raw_text"))related.addView(button("이 메일의 통화 관련성을 직접 확인") {
                val expected=JSONObject(r.toString());confirm("메일 관련성 확인","발신자·업무와 원문을 직접 확인한 후 이 통화의 근거로 연결해요. 메일 속 제안은 업무 상태에 자동 적용하지 않아요.","근거로 연결") {
                    perform { createStore().use { it.confirmNaverCandidate(id,expected,candidate.getString("digest")) };"확인한 메일 원문을 이 통화의 근거로 저장했어요. 분석 포함은 별도로 동의해야 해요." }
                }
            })
        }
        items(r.optJSONArray("related")).forEach { source ->
            related.addView(label(source.getString("kind")+" · "+source.optString("title")+if(source.optInt("same_phone")==1)" · 동일 번호 확인 후보" else " · 사용자 연결, 상대 확인 필요"))
            related.addView(button("연결한 원문 보기") { textDialog("관련 자료 원문",source.getString("raw_text")) })
            related.addView(button("이 근거를 읽고 필요 서류 작성") { itemForm(null,"DOCUMENT",source=source) })
            related.addView(button("이 근거를 읽고 todo 작성") { itemForm(null,"TODO",source=source) })
            related.addView(label("이 원문만으로 업무 완료나 문서 수신을 자동 확정하지 않아요."))
            related.addView(button("이 원문을 분석 근거에 포함 · 별도 동의") { confirm("관련 원문 분석 포함","이 원문을 현재 통화의 분석 요청에 함께 포함해요. 요청 전달 동의와 현재 계정·범위를 다시 확인하며 업무는 자동 완료하지 않아요.","분석 포함에 동의") { perform { createStore().use { it.setEvidenceAnalysisConsent(id,source.getString("source_key"),true) };"분석 포함 동의를 저장했어요. 기존 요청을 자동 재전송하지 않았어요." } } })
            related.addView(button("이 자료의 후속 분석 포함 중단") { perform { createStore().use { it.setEvidenceAnalysisConsent(id,source.getString("source_key"),false) };"이 자료의 신규 분석 포함을 중단했어요. 이미 전달된 외부 사본은 소급 회수하지 않아요." } })
        }
        body.addView(button("저장 이력 다시 읽기") { reload() })
        body.addView(button("이 계정의 일일보고 검토") { startActivity(Intent(this,DailyReportActivity::class.java)) })
    }
    private fun show(title:String,body:LinearLayout,yes:String,save:()->Unit) {
        if(!current())return;dialog?.dismiss()
        val d=AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(body) }).setNegativeButton("취소",null).setPositiveButton(yes,null).create()
        dialog=d;ProtectedDialogWindow.show(d);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if(current())save() }
    }
    private fun confirm(title:String,text:String,yes:String,save:()->Unit) { val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(label(text)) };show(title,body,yes) { dialog?.dismiss();save() } }
    private fun textDialog(title:String,value:String) { val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(label(value)) };show(title,body,"닫기") { dialog?.dismiss() } }
    private fun workForm() {
        val expected=JSONObject(checkNotNull(data).toString());val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(label("같은 번호나 이름만으로 기존 업무를 선택하지 않아요. 새 업무 또는 직접 확인한 기존 업무를 선택하세요."))
        val spinner=Spinner(this);val choices=items(cases);spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("새 업무로 연결")+choices.map { it.getString("label") });body.addView(spinner)
        val person=field(body,"사람 이름 · 모르면 비워 두기");val company=field(body,"회사 · 모르면 비워 두기");val work=field(body,"직접 확인한 업무 제목")
        val phone=field(body,"직접 확인 번호 · 비우면 기존 확인 유지")
        show("사람·업무 직접 확인",body,"확인한 연결 저장") {
            val selected=choices.getOrNull(spinner.selectedItemPosition-1)?.getString("id")
            val p=person.text.toString();val c=company.text.toString();val w=work.text.toString();val number=phone.text.toString()
            dialog?.dismiss();perform { createStore().use { it.confirmWork(id,expected,p,c,w,number,selected) };"확인한 사람·업무 연결을 저장했어요. 기존 메모·완료는 유지했어요." }
        }
    }
    private fun itemForm(existing:JSONObject?,kind:String,source:JSONObject?=null,proposal:JSONObject?=null) {
        val expected=JSONObject(checkNotNull(data).toString());val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        if(source!=null || proposal!=null)body.addView(label("원문의 뜻과 사람·업무를 직접 확인한 항목만 작성해요. 인용 일치는 의미가 맞다는 보증이 아니며, 원문과 분석 제안은 그대로 보존해요."))
        val title=field(body,if(kind=="DOCUMENT")"필요 서류 이름" else "todo 제목",existing?.optString("title").orEmpty())
        val edition=field(body,"문서 판 · 모르면 비워 두기",existing?.optString("edition").orEmpty())
        val due=field(body,"기한 · 직접 확인한 값만 입력",existing?.optString("due_text").orEmpty())
        val quote=if(source!=null || proposal!=null)field(body,"원문에서 확인한 정확한 근거 문장",multi=true) else null
        val states=listOf("open","in_progress","waiting","needs_confirmation","done");val status=Spinner(this).apply { adapter=ArrayAdapter(this@RecordingWorkflowActivity,android.R.layout.simple_spinner_dropdown_item,listOf("진행 전","진행 중","대기","확인 필요","완료"));setSelection(states.indexOf(existing?.optString("status") ?: "open").coerceAtLeast(0)) };body.addView(status)
        show(if(kind=="DOCUMENT")"필요 서류 직접 관리" else "todo 직접 관리",body,"저장") {
            val t=title.text.toString();val e=edition.text.toString();val d=due.text.toString();val q=quote?.text?.toString().orEmpty();val selectedStatus=states[status.selectedItemPosition]
            dialog?.dismiss();perform { createStore().use { store ->
                if(existing==null)store.createItem(id,expected,kind,t,e,d,source?.getString("source_key"),q,proposal?.getString("digest"),selectedStatus)
                else store.editItem(existing.getString("id"),existing.getLong("revision"),t,selectedStatus,d,e)
            };"직접 입력한 항목을 저장했어요. 다른 업무와 완료 상태는 바꾸지 않았어요." }
        }
    }
    private fun relatedForm(kind:String,prefill:String="") {
        if(!current())return;val expected=JSONObject(checkNotNull(data).toString());val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(label("이 통화에 연결할 자료를 직접 선택해요. 같은 번호만으로 사람·업무를 합치지 않으며, 원문 속 지시를 실행하지 않아요."))
        val title=field(body,"자료 제목");val phone=field(body,"문자 상대 번호 · 모르면 비워 두기");val sent=field(body,"자료 시각 · 모르면 비워 두기");val text=field(body,"직접 선택한 원문",prefill,true)
        val include=CheckBox(this).apply { this.text="이 원문을 분석 요청의 근거에 포함하는 데 동의해요. 선택하지 않으면 기기에만 연결해요.";textSize=18f;minHeight=dp(56) };body.addView(include)
        show(if(kind=="SMS")"관련 문자 직접 선택" else "관련 메일 직접 선택",body,"이 통화의 근거로 연결") {
            val t=text.text.toString();val number=phone.text.toString();val at=sent.text.toString();val heading=title.text.toString();val allowed=include.isChecked
            dialog?.dismiss();perform { createStore().use { it.addRelated(id,expected,kind,t,number,at,heading,includeAnalysis=allowed) };"선택 원문을 근거로 연결했어요. 필요 서류·완료는 자동 확정하지 않았어요." }
        }
    }
    private fun summaryForm(proposal:JSONObject?) {
        val expected=JSONObject(checkNotNull(data).toString());val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(label("전사·분석 제안을 읽고 본인이 확인한 문장만 저장해요. 외부 실행·작성자 증명은 별도이며, 메모와 완료는 바꾸지 않아요."))
        val value=field(body,"내 확인 요약",proposal?.optString("summary_text")?.takeUnless { it=="null" }.orEmpty(),true)
        val confirmed=CheckBox(this).apply { text="제가 읽고 확인한 요약이에요.";textSize=18f;minHeight=dp(56) };body.addView(confirmed)
        show("내 확인 요약",body,"직접 확인 요약 저장") { if(!confirmed.isChecked)return@show;val text=value.text.toString();dialog?.dismiss();perform {
            createStore().use { it.reviewOwnSummary(id,expected,text,proposal?.getString("digest")) };"직접 확인 요약을 새 이력으로 저장했어요. 실제 모델 인증이나 자동 업무 적용을 뜻하지 않아요."
        } }
    }
    private fun progressForm() {
        if(!current())return;val expected=JSONObject(checkNotNull(data).toString())
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(label("${expected.optString("work_label")}의 진행 상태를 직접 바꿔요. 통화의 메모·완료와 개별 서류·todo는 그대로 유지해요."))
        val states=listOf("open","waiting","in_progress","needs_confirmation","done")
        val state=Spinner(this).apply { adapter=ArrayAdapter(this@RecordingWorkflowActivity,android.R.layout.simple_spinner_dropdown_item,states.map { kr.callbrief.core.TaskStatus.fromWire(it).label });setSelection(states.indexOf(expected.optString("task_status")).coerceAtLeast(0)) };body.addView(state)
        val note=field(body,"직접 수정 이유 · 선택")
        val confirmed=CheckBox(this).apply { text="전체 업무의 이 상태를 제가 확인했어요. 개별 서류 완료와 구분했어요.";textSize=18f;minHeight=dp(56) };body.addView(confirmed)
        show("업무 진행 직접 수정",body,"직접 확인 상태 저장") {
            if(!confirmed.isChecked)return@show;val status=states[state.selectedItemPosition];val reason=note.text.toString();dialog?.dismiss()
            perform { createStore().use { it.editBusinessProgress(id,expected,status,reason,true) };"직접 확인한 업무 진행 상태를 새 이력으로 저장했어요. 통화 메모·완료와 서류·todo는 유지해요." }
        }
    }
    private fun businessForm(review:JSONObject) {
        if(!current())return
        if(!review.getBoolean("eligible")) {
            textDialog("분석 제안 · 반영 보류","현재 원본·결과·사람/업무·날짜·수동 버전을 확인해야 해요.\n기록은 바꾸지 않았어요.\n\n"+review.getString("raw_text"));return
        }
        val expected=JSONObject(checkNotNull(data).toString())
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(label("${expected.optString("person_label")} · ${expected.optString("work_label")}\n원문을 읽고 이 사람·업무의 내용인지 직접 확인해 주세요. 자동 완료·자동 반영은 하지 않아요."))
        data class Selection(val claim:JSONObject,val chosen:CheckBox,val title:EditText,val edition:EditText,val due:EditText,val state:Spinner,val target:Spinner,val targets:List<JSONObject>,val done:CheckBox)
        val selections=mutableListOf<Selection>();val states=listOf("open","waiting","in_progress","needs_confirmation","done")
        items(review.getJSONArray("claims")).forEach { claim ->
            val card=block(body,when(claim.getString("suggested_kind")){"DOCUMENT"->"필요 서류 제안";"TODO"->"todo 제안";"PROGRESS"->"진행 상태 제안";else->"의미·연결 확인 필요"})
            card.addView(label(claim.getString("value")))
            items(claim.getJSONArray("evidence")).forEach { q->card.addView(label("원문 근거: ${q.getString("quote")}")) }
            if(!claim.getBoolean("selectable")) { card.addView(label("불확실하거나 연결 확인이 필요한 제안이에요. 현재 업무로 반영하지 않아요."));return@forEach }
            val chosen=CheckBox(this).apply { text="이 항목을 직접 확인해 저장";textSize=18f;minHeight=dp(56) };card.addView(chosen)
            val title=field(card,"저장할 내용",claim.getString("value"));val edition=field(card,"문서 판 · 원문과 직접 대조")
            val due=field(card,"기한 표현 · 시간대는 추정하지 않아요",claim.getString("due_display"))
            val state=Spinner(this).apply { adapter=ArrayAdapter(this@RecordingWorkflowActivity,android.R.layout.simple_spinner_dropdown_item,states.map { kr.callbrief.core.TaskStatus.fromWire(it).label })
                setSelection(states.indexOf(claim.optString("status")).takeIf { it>=0 && states[it]!="done" } ?: 3) };card.addView(label("직접 확인 상태"));card.addView(state)
            val targets=items(expected.optJSONArray("items")).filter { it.getString("kind")==claim.getString("suggested_kind") }
            val target=Spinner(this).apply { adapter=ArrayAdapter(this@RecordingWorkflowActivity,android.R.layout.simple_spinner_dropdown_item,listOf("새 항목으로 저장")+targets.map { it.getString("title")+" · "+it.getString("status") }) }
            if(claim.getString("suggested_kind")!="PROGRESS") { card.addView(label("반영할 항목 · 이름만으로 합치지 않아요"));card.addView(target) }
            val done=CheckBox(this).apply { text="완료를 선택한 경우, 이 항목의 완료를 제가 확인했어요.";textSize=18f;minHeight=dp(56) };card.addView(done)
            card.addView(label("직접 수정·완료한 기존 항목과 진행 잠금은 유지해요. 변경이 필요하면 별도 직접 수정 화면을 사용해 주세요."))
            selections+=Selection(claim,chosen,title,edition,due,state,target,targets,done)
        }
        val summary=field(body,"내 확인 요약",review.getString("summary"),true)
        val saveSummary=CheckBox(this).apply { text="이 요약도 읽고 직접 확인한 새 이력으로 저장";textSize=18f;minHeight=dp(56) };body.addView(saveSummary)
        val confirmed=CheckBox(this).apply { text="선택한 내용의 의미·사람·업무·원문을 직접 확인했어요.";textSize=18f;minHeight=dp(56) };body.addView(confirmed)
        show("분석 제안 검토 · 선택 항목만 저장",body,"확인한 항목 저장") {
            if(!confirmed.isChecked)return@show
            val actions=JSONArray()
            selections.filter { it.chosen.isChecked }.forEach { s ->
                val target=s.targets.getOrNull(s.target.selectedItemPosition-1)
                actions.put(JSONObject().put("claim_id",s.claim.getString("claim_id")).put("kind",s.claim.getString("suggested_kind"))
                    .put("title",s.title.text.toString()).put("edition",s.edition.text.toString()).put("due",s.due.text.toString())
                    .put("status",states[s.state.selectedItemPosition]).put("completion_confirmed",s.done.isChecked)
                    .put("target_id",target?.getString("id") ?: JSONObject.NULL).put("target_revision",target?.getLong("revision") ?: 0))
            }
            val text=if(saveSummary.isChecked)summary.text.toString() else null
            if(actions.length()==0 && text.isNullOrBlank())return@show
            dialog?.dismiss();perform { createStore().use { it.applyBusinessReview(id,expected,review.getString("digest"),actions,text,true) }
                "선택해 직접 확인한 항목·근거·이력을 함께 저장했어요. 메모·완료 잠금과 원문은 유지해요." }
        }
    }
    private fun openRequestedTodo() {
        val wanted=requestedTodo ?: return
        if(todoRouteConsumed || !current() || busy)return
        todoRouteConsumed=true
        val snapshot=data ?: return
        val item=items(snapshot.optJSONArray("items")).singleOrNull {
            snapshot.optString("recording_id")==id && it.optString("recording_id")==id && it.optString("id")==wanted && it.optString("kind")=="TODO"
        }
        if(item==null) { message="선택한 할일을 현재 통화에서 확인하지 못했어요. 기존 기록은 유지해요.";render();return }
        itemForm(item,"TODO")
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("todoRouteConsumed",todoRouteConsumed);super.onSaveInstanceState(outState)
    }
    private fun reload()=perform { "저장한 통화·분석·업무 이력을 다시 읽었어요." }
    private fun perform(action:suspend ()->String) {
        if(!current()||busy)return;val ticket=++generation;busy=true;render()
        operation=lifecycleScope.launch {
            var snapshotRead=false
            try {
                val result=withContext(Dispatchers.IO) { action() }
                ensureActive();if(!current()||generation!=ticket)return@launch
                val saved=withContext(Dispatchers.IO) { createStore().use { it.snapshot(id) to it.cases() } }
                ensureActive();if(current()&&generation==ticket) { data=saved.first;cases=saved.second;message=result;snapshotRead=true }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(current()&&generation==ticket)message="저장·전달을 확인하지 못했어요. 이전 기록과 완료는 유지하며 다시 읽어 확인해 주세요." }
            finally { analysis=null;if(generation==ticket) { busy=false;if(current()) { render();if(snapshotRead)openRequestedTodo() } } }
        }
    }
    override fun onResume() { super.onResume();if(::session.isInitialized) { if(!current()) { data=null;dialog?.dismiss();finish() } else if(!busy)reload() } }
    override fun onStop() { ++generation;analysis?.close();operation?.cancel();busy=false;dialog?.dismiss();super.onStop() }
    override fun onDestroy() { closed=true;++generation;analysis?.close();operation?.cancel();dialog?.dismiss();super.onDestroy() }
    companion object { fun open(context:Context,recording:String,todoId:String?=null)=context.startActivity(Intent(context,RecordingWorkflowActivity::class.java).putExtra("recording_id",recording).apply { todoId?.let { putExtra("edit_todo_id",it) } }) }
}
