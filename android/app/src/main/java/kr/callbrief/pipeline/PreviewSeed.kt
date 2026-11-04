package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.SecureSettings
import kr.co.callsummary.core.android.AndroidSqlPort
import java.time.LocalDate

/** Local design preview only (BuildConfig.LOCAL_PREVIEW): a made-up day on a building site so every screen has
 * content — overdue / today / later / undated to-dos, three companies, a staff member, one call still being
 * summarised, one from yesterday. Setup is marked done. All names and numbers are invented (the numbers show the dial buttons); nothing leaves the phone. */
internal object PreviewSeed {
    private class Call(val day:Int,val h:Int,val m:Int,val minutes:Int,val who:String,val text:String,val a:ClaudeCallAnalysis?)

    private const val DONE="previewSeeded"
    /** Resumable: each record is stored and analysed only once, so a run cut short (app killed) finishes next time. */
    fun ensure(storage:Context) {
        if(SecureSettings(storage).snapshot().optBoolean(DONE,false)) return
        SecureSettings(storage).update {
            it.put("treeUri","content://preview/tree").put(PipelineSettingsKeys.CONSENT_GRANTED,true)
                .put(PipelineSettingsKeys.MODEL_STATE,"READY").put(PipelineSettingsKeys.LAST_ANALYZED_MS,System.currentTimeMillis()-40*60_000L)
        }
        val today=CallUi.today()
        fun d(days:Long)=today.plusDays(days).toString()
        fun cp(name:String,company:String?,role:String?,staff:Boolean=false)=ClaudeCallAnalysis.Counterpart(name,company,role,staff)
        fun task(title:String,due:String?,quote:String,company:String?)=ClaudeCallAnalysis.Task("create",null,title,due,quote,company)
        val calls=listOf(
            Call(-1,16,10,4,"010-1111-2222","네 과장님, 철근 단가표는 제가 정리해서 다시 연락드릴게요.",
                ClaudeCallAnalysis("△△철강 김민수 과장과 철근 단가를 이야기했어요. 단가표를 정리해 다시 연락하기로 했어요.",listOf("철근 단가 인상 요청"),"김민수","△△철강",
                    emptyList(),listOf(task("철근 단가표 정리해서 회신하기",d(-1),"단가표는 제가 정리해서","△△철강")),cp("김민수","△△철강","과장"),
                    "3층 슬래브 타설 준비 중, 철근 반입 금요일","철근 단가 재견적 진행 중","철근")),
            Call(0,8,40,6,"010-1111-2222","네 김민수 과장님, 수정 견적서는 오늘 중으로 보내드릴게요. 반입은 금요일 오전으로 맞춰주세요.",
                ClaudeCallAnalysis("김민수 과장과 철근 반입 일정을 정했어요. 수정 견적서를 오늘 보내기로 했고, 반입은 금요일 오전이에요.",
                    listOf("철근 반입 금요일 오전","수정 견적서 오늘 발송"),"김민수","△△철강",
                    listOf(ClaudeCallAnalysis.Promise("수정 견적서 보내기",d(0),"me","수정 견적서는 오늘 중으로")),
                    listOf(task("김민수 과장에게 수정 견적서 보내기",d(0),"수정 견적서는 오늘 중으로","△△철강")),cp("김민수","△△철강","과장"),
                    companyMemo="철근 단가 재견적 진행 중, 금요일 오전 반입",companyTrade="철근")),
            Call(0,9,55,3,"010-2222-3333","소장님, 3층 타설 일정표는 내일까지 공유드릴게요.",
                ClaudeCallAnalysis("박지훈 소장과 3층 슬래브 타설 일정을 맞췄어요. 일정표를 내일까지 공유하기로 했어요.",listOf("타설은 다음 주 화요일 예정"),"박지훈",null,
                    listOf(ClaudeCallAnalysis.Promise("타설 일정표 공유",d(1),"me","타설 일정표는 내일까지")),
                    listOf(task("3층 타설 일정표 공유",d(1),"타설 일정표는 내일까지",null)),cp("박지훈",null,"소장",staff=true),
                    siteMemo="3층 슬래브 타설 다음 주 화요일 예정, 철근 반입 금요일 오전")),
            Call(0,11,20,5,"010-4444-5555","분전반 도면 수정본은 어제 주시기로 하셨는데 아직 못 받았어요. 오늘 오후까지 꼭 부탁드려요.",
                ClaudeCallAnalysis("◇◇전기 이영호 대리에게 분전반 도면 수정본을 다시 요청했어요. 원래 어제까지 받기로 했던 것이에요.",listOf("도면 수정본 지연"),"이영호","◇◇전기",
                    listOf(ClaudeCallAnalysis.Promise("분전반 도면 수정본 보내기",d(-1),"them","분전반 도면 수정본은 어제 주시기로")),
                    listOf(task("이영호 대리 분전반 도면 수정본 회신 확인",d(-1),"분전반 도면 수정본은 어제 주시기로","◇◇전기")),cp("이영호","◇◇전기","대리"),
                    companyMemo="분전반 도면 수정 지연",companyTrade="전기")),
            Call(0,14,5,7,"010-5555-6666","금요일 레미콘 배차는 수요일까지 확정해서 전화드릴게요. 단가표도 한 번 보내주세요.",
                ClaudeCallAnalysis("○○레미콘 최은정 팀장과 금요일 배차를 이야기했어요. 수요일까지 확정해 연락하기로 했고, 단가표를 받기로 했어요.",
                    listOf("금요일 레미콘 배차","단가표 요청"),"최은정","○○레미콘",
                    listOf(ClaudeCallAnalysis.Promise("배차 확정 전화",d(3),"me","수요일까지 확정해서 전화드릴게요")),
                    listOf(task("레미콘 배차 확정 전화",d(3),"수요일까지 확정해서 전화드릴게요","○○레미콘"),task("최은정 팀장 레미콘 단가표 회신 확인",null,"단가표도 한 번 보내주세요","○○레미콘")),
                    cp("최은정","○○레미콘","팀장"),companyMemo="금요일 배차 협의 중",companyTrade="타설")),
            Call(0,16,30,2,"010-3344-5566","네, 알겠습니다. 확인하고 다시 연락드릴게요.",null))
        AndroidSqlPort(storage,GeneralLocalStore.DATABASE,storage.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('preview',?,'content://preview/tree',1,1,1)",GeneralLocalStore.OWNER)
            calls.indices.forEach { i -> p.execute("INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'preview',?,?,'audio/mp4',10,0,0,1,1,1)",
                "preview-$i",GeneralLocalStore.OWNER,"preview-$i","preview-$i.m4a") }
            null
        } }
        // A text thread and a mail, so the lists show all three kinds.
        val smsAt=LocalDate.parse(d(0)).atTime(15,20).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        val sms="[오후 3:20 받은 문자] 부장님 철근 반입 금요일 오전 8시로 확정했습니다. 차량 2대 들어갑니다.\n[오후 3:24 보낸 문자] 네 알겠습니다. 하차 위치는 현장 북문으로 해주세요."
        CallRecordStore(storage) { smsAt+300_000 }.use { s ->
            s.insertMessage("${MessageKind.SMS}01011110001:1",smsAt,"01011110001",sms)
            s.day(today).first { it.kind=="문자" }.takeIf { it.state!=CallRecordStore.ANALYZED }?.let { r -> s.applyAnalysis(r.recordId,ClaudeCallAnalysis("△△철강 김민수 과장이 철근 반입을 금요일 오전 8시로 확정했어요. 하차 위치는 현장 북문으로 알려줬어요.",
                listOf("차량 2대"),"김민수","△△철강",emptyList(),listOf(task("금요일 오전 8시 철근 반입 확인",d(1),"철근 반입 금요일 오전 8시로 확정","△△철강")),cp("김민수","△△철강","과장"))) }
        }
        val mailAt=LocalDate.parse(d(0)).atTime(13,10).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        CallRecordStore(storage) { mailAt+300_000 }.use { s ->
            s.insertMessage("${MessageKind.MAIL}1:1",mailAt,"이영호","제목: 분전반 도면 수정본 송부 지연 안내\n보낸 사람: 이영호 lee@electric.co.kr\n\n부장님, 분전반 도면 수정본은 내일 오전 중 보내드리겠습니다.")
            s.day(today).first { it.kind=="메일" }.takeIf { it.state!=CallRecordStore.ANALYZED }?.let { r -> s.applyAnalysis(r.recordId,ClaudeCallAnalysis("◇◇전기 이영호 대리가 분전반 도면 수정본을 내일 오전에 보내겠다고 메일을 보냈어요.",
                emptyList(),"이영호","◇◇전기",emptyList(),emptyList(),cp("이영호","◇◇전기","대리"))) }
        }
        calls.forEachIndexed { i,c ->
            val at=LocalDate.parse(d(c.day.toLong())).atTime(c.h,c.m).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
            val t=CallTranscript("preview-$i",1,"%064d".format(i),at,c.minutes*60_000L,c.who,c.text,listOf(CallTranscript.Segment(0,1000,c.text)),"preview","0".repeat(64),true)
            // Stored as if analysed right after the call, so "요청" dates follow the calls.
            CallRecordStore(storage) { at+c.minutes*60_000L }.use { s -> s.insert(t)
                if(s.record(CallRecordStore.recordId(t))?.state!=CallRecordStore.ANALYZED) c.a?.let { s.applyAnalysis(CallRecordStore.recordId(t),it) } }
        }
        SecureSettings(storage).update { it.put(DONE,true) }
    }
}
