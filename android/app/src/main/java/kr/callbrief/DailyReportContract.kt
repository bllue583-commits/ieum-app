package kr.callbrief

import kr.callbrief.kakao.core.DeliveryCoordinator
import kr.co.callsummary.core.JsonCanonical
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.Duration

/** Display date/zone and 06:00 target are separate from the immutable, continuous UTC coverage. */
internal data class ReportWindow(val date: LocalDate, val zone: ZoneId,
    val from:Instant=date.atStartOfDay(zone).toInstant(),
    val until:Instant=date.plusDays(1).atStartOfDay(zone).toInstant()) {
    init { require(until.isAfter(from)) { "REPORT_WINDOW_MUST_ADVANCE_UTC" } }
    val due: Instant get() = date.plusDays(1).atTime(LocalTime.of(6,0)).atZone(zone).toInstant()
    val calendarDay:Boolean get()=from==date.atStartOfDay(zone).toInstant() && until==date.plusDays(1).atStartOfDay(zone).toInstant()
    // A normal 25-hour DST civil day is still one day; extended catch-up/zone ranges are held.
    val backlogReview:Boolean get()=!calendarDay && Duration.between(from,until)>Duration.ofDays(1)
    fun titleKo():String=when { backlogReview -> "밀린 기록 검토 · ${from.atZone(zone).toLocalDate()} ~ ${until.atZone(zone).toLocalDate()}"
        !calendarDay -> "시간대 변경 구간 · $date";else -> "$date 통화 보고" }
    fun rangeKo()="조회 범위: ${from.atZone(zone)} 이상 ~ ${until.atZone(zone)} 미만\nUTC: $from 이상 ~ $until 미만"
    fun bodyWithRange(text:String):String {
        if(calendarDay)return text
        val header=titleKo()+if(backlogReview)" · 자동 발송 제외\n" else "\n"
        val prefix=header+rangeKo()
        return if(text.startsWith(prefix))text else "$prefix\n\n$text"
    }
    companion object {
        fun next(now: Instant, zone: ZoneId): ReportWindow {
            val today=now.atZone(zone).toLocalDate()
            val date=if(now.isBefore(today.atTime(6,0).atZone(zone).toInstant()))today.minusDays(1) else today
            return ReportWindow(date,zone)
        }
        fun slot(profile: String,date: LocalDate)=DeliveryCoordinator.sha256("ieum-reviewed-daily-v2\n$profile\n$date")
    }
}

internal enum class ReportReadiness { PLANNED, NO_RECORDINGS, WAITING_ANALYSIS, WAITING_REVIEW, SOURCE_FAILURE, REVIEWED, STALE_REVIEW, BACKLOG_REVIEW, CANCELLED }
internal enum class ReportDelivery { NOT_STORED, LOCAL_STORED, SEND_WAITING, SENDING, RETRY, SENT, FAILED, UNKNOWN, CANCELLED }
internal data class ReviewedReportSource(val id: String,val audioHash: String,val sourceVersion: Long,
    val summaryVersion: Long,val summaryHash: String,val reviewReceipt: String,val headRevision: Long,
    val userRevision: Long,val lockRevision: Long,val text: String,val taskRevision:Long=0,
    val taskStatus:String="",val manualStatus:String="",val boardRevision:Long=0,
    val personId:String="",val companyId:String="",val caseId:String="",val summaryOrigin:String="CANONICAL_ACCEPTED_BY_USER") {
    fun validate() {
        require(id.isNotBlank() && audioHash.matches(Regex("[0-9a-f]{64}")) && sourceVersion>0)
        require(summaryVersion>0 && summaryHash==DeliveryCoordinator.sha256(text) && reviewReceipt.isNotBlank())
        require(headRevision>=0 && userRevision>=0 && lockRevision>=0 && text.isNotBlank())
    }
    fun proof()=listOf(id,audioHash,sourceVersion,summaryVersion,summaryHash,reviewReceipt,headRevision,userRevision,lockRevision,
        taskRevision,taskStatus,manualStatus,boardRevision,personId,companyId,caseId)+
        if(summaryOrigin=="CANONICAL_ACCEPTED_BY_USER")emptyList() else listOf(summaryOrigin)
    override fun toString()="ReviewedReportSource[content withheld]"
}
internal data class ReportPendingSource(val id:String,val sourceVersion:Long,val audioHash:String,
    val headRevision:Long,val userRevision:Long,val lockRevision:Long,val reason:String,val state:String,
    val originalTimeKnown:Boolean,val requestId:String="",val resultDigest:String="") {
    fun proof()=listOf(id,sourceVersion,audioHash,headRevision,userRevision,lockRevision,reason,state,originalTimeKnown,requestId,resultDigest)
    fun textKo():String="통화 ${id.takeLast(8)} · "+when(reason) {
        "ANALYSIS_FAILED","CURRENT_REQUEST_FAILED"->"분석 실패 · 기존 확인 내용 유지"
        "CURRENT_REQUEST_UNKNOWN"->"전달·결과 불확실 · 자동 재시도 없음"
        "ANALYSIS_PENDING","CURRENT_REQUEST_PENDING"->"분석 대기"
        "IDENTITY_TIME_UNCONFIRMED"->"사람·업무 또는 날짜 확인 필요"
        "NATIVE_BINDING_MISSING"->"원본 연결 정보 확인 필요 · 대상에서 빠뜨리지 않음"
        "SOURCE_UNAVAILABLE"->"원본 사용 가능 여부 확인 필요"
        "MANUAL_REVIEW_REQUIRED"->"직접 수정한 내용 재확인 필요"
        "INVENTORY_UNAVAILABLE"->"목록 조회 실패 · 전체 대상 수 미확정"
        else->"결과·근거 검토 대기"
    }+if(!originalTimeKnown)" · 통화 날짜 미확인, 이 날짜로 확정하지 않음" else ""
    override fun toString()="ReportPendingSource[content withheld]"
}
internal data class DailyReportInput(val readiness: ReportReadiness,val sources: List<ReviewedReportSource>,
    val expectedCount: Int,val pendingCount: Int,val policyHash: String,val unresolved:List<ReportPendingSource> = emptyList()) {
    init { require(expectedCount>=0 && pendingCount>=0 && policyHash.matches(Regex("[0-9a-f]{64}"))) }
    val fullyReviewed: Boolean get()=readiness==ReportReadiness.REVIEWED && pendingCount==0 && unresolved.isEmpty() &&
        sources.isNotEmpty() && sources.size==expectedCount && sources.map { it.id }.distinct().size==sources.size &&
        sources.all { runCatching { it.validate() }.isSuccess }
    val basisHash: String get()=JsonCanonical.digest(listOf(readiness.name,expectedCount,pendingCount,policyHash,
        sources.sortedBy { it.id }.map { it.proof() })+if(unresolved.isEmpty())emptyList() else listOf(unresolved.sortedBy { it.id }.map { it.proof() }))
    /** Read-only partial copy. Never used as finalized payload or as evidence of overall completion. */
    fun partialPreview():String {
        check(!fullyReviewed)
        return buildString {
            append("부분 보고 · 전체 완료 아님\n")
            append(if(readiness==ReportReadiness.SOURCE_FAILURE)"전체 목록 확인 실패 · 대상 수 미확정" else "대상 ${expectedCount}개 · 확인 내용 ${sources.size}개 · 분석·검토 대기 ${pendingCount}개")
            append("\n미완료 통화는 아래에 남겨 두었어요. 현재 상태와 이전에 직접 확인한 내용은 함께 표시해요.\n\n")
            sources.sortedBy { it.id }.forEach { append("통화 ${it.id.takeLast(8)} · 이전/현재 직접 확인 내용\n${it.text}\n\n") }
            unresolved.sortedBy { it.id }.forEach { append(it.textKo()+"\n") }
            if(unresolved.isEmpty() && pendingCount>0)append("미확인 ${pendingCount}개 · 상세 목록을 읽지 못했어요. 완료로 간주하지 않아요.\n")
            append("\n부분 내용 조회 · 전송 대상 아님")
        }
    }
    fun preview(): String {
        check(fullyReviewed)
        val full=sources.sortedBy { it.id }.joinToString("\n\n") { source ->
            val status=source.manualStatus.ifBlank { source.taskStatus }
            source.text+when(status) { "done" -> "\n앱에서 확인한 현재 상태: 완료";"waiting" -> "\n앱에서 확인한 현재 상태: 대기"
                "in_progress" -> "\n앱에서 확인한 현재 상태: 진행 중";"needs_confirmation" -> "\n앱에서 확인한 현재 상태: 확인 필요";else -> "" }
        }
        val end=if(full.length>16_000 && Character.isHighSurrogate(full[15_999]))15_999 else minOf(full.length,16_000)
        return full.substring(0,end)
    }
    override fun toString()="DailyReportInput[$readiness, sources=$expectedCount; content withheld]"
}
internal data class DailyReportConfig(val enabled: Boolean,val generation: Long,val cursor: LocalDate?,val zone: String,val legacyHold:Boolean=false)
internal data class SavedDailyReport(val id: String,val window: ReportWindow,val observed: Instant?,
    val readiness: ReportReadiness,val delivery: ReportDelivery,val basisHash: String,val payloadHash: String,
    val attempts: Int,val retryAt: Instant,val note: String,val claim: String,val claimBoot: Long,val claimUntil: Long) {
    fun stateKo(): String=when(delivery) {
        ReportDelivery.SENT -> if(readiness==ReportReadiness.STALE_REVIEW)"이전 보고 전송 성공 · 최신 변경은 검토 필요"
            else "전송 성공 · 카카오 API 수락 확인, 읽음 여부 미확인"
        ReportDelivery.UNKNOWN -> "전송 결과 불확실 · 중복 방지를 위해 자동 재전송하지 않음"
        ReportDelivery.SENDING -> "전송 진행 중 · 다음 상태 확인에서 중단 여부 확인"
        ReportDelivery.FAILED -> "전송 실패 · 앱의 보고는 보관됨"
        ReportDelivery.SEND_WAITING,ReportDelivery.RETRY -> "앱에 보고 보관 · 전송 대기"
        ReportDelivery.LOCAL_STORED -> "앱에 보고 보관"
        ReportDelivery.CANCELLED -> "작업 취소 · 보관된 보고 유지"
        ReportDelivery.NOT_STORED -> when(readiness) {
            ReportReadiness.PLANNED -> "오전 6시 목표 · 실행 대기"
            ReportReadiness.NO_RECORDINGS -> "해당 날짜 기록 없음 · 완료 보고 생성 없음"
            ReportReadiness.WAITING_ANALYSIS -> "대상 구간 분석 미완료 · 완료 보고 생성 없음"
            ReportReadiness.WAITING_REVIEW -> "결과 검토 대기 · 완료 보고 생성 없음"
            ReportReadiness.SOURCE_FAILURE -> "기록 상태 확인 실패 · 기존 자료 유지"
            ReportReadiness.STALE_REVIEW -> "검토 뒤 자료 변경 · 전송 보류"
            ReportReadiness.CANCELLED -> "작업 취소"
            ReportReadiness.REVIEWED -> "검토된 결과 준비 · 보고 내용 확인 필요"
            ReportReadiness.BACKLOG_REVIEW -> "밀린 기록 검토 · 자동 발송 제외"
        }
    }.let { if(window.backlogReview && !it.startsWith("밀린 기록 검토"))"밀린 기록 검토 · 자동 발송 제외\n$it" else it }
}
internal data class ReportEnvironment(val now: Instant,val zone: ZoneId,val online: Boolean=true,
    val doze: Boolean=false,val batteryOkay: Boolean=true,val elapsed: Long=0,val boot: Long=0,
    val trigger: String="RECONCILE")
