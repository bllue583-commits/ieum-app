package kr.callbrief.pipeline

import android.app.AlertDialog
import android.widget.LinearLayout
import kr.callbrief.ProtectedDialogWindow
import kr.callbrief.SecureSettings

/** Open review items and case suggestions. Every action here is an explicit user command. */
class PipelineReviewActivity:PipelineScreenActivity() {
    private class Model(val items:List<PipelineReviewActions.Item>,val awaiting:List<Map<String,Any?>>,val cases:Map<String,String>,val titles:Map<String,String>)
    override fun refresh() {
        io("검토 목록을 읽지 못했어요. 저장된 내용은 바꾸지 않았어요.",{
            PipelineApplyStore(storage).use { s ->
                val a=PipelineReviewActions(storage,s)
                val items=a.open()
                Model(items,a.awaitingCase(),s.caseLabels(),items.mapNotNull { i->(i.change["taskId"] as? String)?.let { id->s.item(id)?.let { id to it["title"].toString() } } }.toMap())
            }
        }) { render(it) }
    }
    private fun kindLabel(i:PipelineReviewActions.Item)=when(i.kind) {
        "SUMMARY"->"요약 갱신 제안";"CREATE"->"새 할 일 제안";else->"할 일 변경 제안" }
    private fun reasonLabel(r:String)=when(r) {
        "DONE_CLAIMED"->"AI가 완료라고 판단했어요. 완료 처리는 직접 확인해야 해요."
        "MANUAL_TASK"->"직접 만들었거나 수정한 할 일이라 덮어쓰지 않았어요."
        "MANUAL_LOCK"->"직접 완료·잠금한 업무라 바꾸지 않았어요."
        "TASK_DONE"->"이미 완료된 할 일이에요."
        "USER_REVIEWED_SUMMARY"->"직접 확인한 요약이 있어 자동으로 바꾸지 않았어요."
        "BOARD_REVISION_CHANGED","TASK_CHANGED_SINCE_TRANSCRIPT"->"분석 이후 할 일이 바뀌어 확인이 필요해요."
        "CASE_CHANGED_BY_USER"->"제안과 다른 업무를 골라 기존 할 일 변경은 확인이 필요해요."
        "CASE_LINK_CHANGED"->"분석 이후 업무 연결이 바뀌었어요."
        else->"확인이 필요해요." }
    private fun render(v:Model) {
        body.removeAllViews();body.addView(label("AI 분석 검토",true))
        if(message.isNotBlank())body.addView(label(message))
        body.addView(label("AI 제안은 확인 전에는 할 일에 반영되지 않아요. 완료 처리는 항상 직접 확인해요."))
        if(v.awaiting.isEmpty() && v.items.isEmpty())body.addView(label("검토할 항목이 없어요."))
        v.awaiting.forEach { a ->
            val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };body.addView(box)
            val sug=PipelineContract.obj(a["suggestion"]);val sugCase=sug["caseId"] as String?
            box.addView(label("업무 연결 확인",true));box.addView(label(a["summary"].toString()))
            box.addView(label("근거: \"${sug["quote"]}\""))
            if(sugCase!=null && v.cases.containsKey(sugCase))
                box.addView(button("제안 업무로 연결: ${v.cases[sugCase]}") { confirmCase(a["analysisId"].toString(),sugCase,null) }.apply { tag="pipeline_confirm_suggested" })
            v.cases.filterKeys { it!=sugCase }.forEach { (id,l)->box.addView(button("다른 업무로 연결: $l") { confirmCase(a["analysisId"].toString(),id,null) }) }
            val name=field("새 업무 이름");box.addView(name)
            box.addView(button("새 업무로 연결") { val t=name.text.toString().trim();if(t.isNotEmpty())confirmCase(a["analysisId"].toString(),null,t) })
            box.addView(button("연결하지 않기") { act("연결하지 않았어요. 내용은 보관돼요.") { s->PipelineCaseConfirmation(storage,s,PipelineApplier(s){SecureSettings(storage).snapshot()},PipelineFetchWork.providers?.index(storage)).decline(a["analysisId"].toString()) } })
        }
        v.items.forEach { i ->
            val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;tag="pipeline_review_${i.id}" };body.addView(box)
            box.addView(label(kindLabel(i),true));box.addView(label(reasonLabel(i.reason)))
            if(i.kind=="SUMMARY")box.addView(label(i.change["summary"].toString()))
            else {
                (i.change["taskId"] as? String)?.let { v.titles[it] }?.let { box.addView(label("현재: $it")) }
                box.addView(label("제안: ${i.change["title"]}${(i.change["due"] as? String)?.let { " · 기한 $it" } ?: ""}"))
                box.addView(label("근거: \"${i.change["quote"]}\""))
            }
            box.addView(button(if(i.reason=="DONE_CLAIMED")"완료로 처리" else "반영") {
                if(i.reason=="DONE_CLAIMED") confirmDone(i) else act("반영했어요.") { s->PipelineReviewActions(storage,s).accept(i.id) }
            }.apply { tag="pipeline_accept_${i.id}" })
            box.addView(button("반영하지 않기") { act("반영하지 않았어요.") { s->PipelineReviewActions(storage,s).dismiss(i.id) } }.apply { tag="pipeline_dismiss_${i.id}" })
        }
        body.addView(button("다시 확인") { refresh() });body.addView(button("돌아가기") { finish() })
    }
    private fun confirmDone(i:PipelineReviewActions.Item) {
        val d=AlertDialog.Builder(this).setTitle("완료 확인").setMessage("'${i.change["title"]}'을(를) 완료로 표시할까요? 직접 확인한 경우에만 완료로 바꿔 주세요.")
            .setPositiveButton("완료로 표시") { _,_->act("완료로 표시했어요.") { s->PipelineReviewActions(storage,s).accept(i.id,confirmCompletion=true) } }
            .setNegativeButton("취소",null).create()
        ProtectedDialogWindow.show(d)
    }
    private fun confirmCase(analysisId:String,caseId:String?,label:String?) =
        act("업무를 연결하고 분석을 반영했어요.") { s->PipelineCaseConfirmation(storage,s,PipelineApplier(s){SecureSettings(storage).snapshot()},PipelineFetchWork.providers?.index(storage)).confirm(analysisId,caseId,label) }
    private fun act(done:String,work:(PipelineApplyStore)->Any?) {
        io("저장하지 못했어요. 상태가 바뀌었을 수 있어요. 다시 확인해 주세요.",{ PipelineApplyStore(storage).use { work(it) } },action=true) { message=done;refresh() }
    }
}
