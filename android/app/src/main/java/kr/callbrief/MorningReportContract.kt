package kr.callbrief

import kr.co.callsummary.core.JsonCanonical
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar windows are always KST, regardless of device zone. reportDay is the delivery day. */
internal enum class MorningReportKind { DAILY, ROLLING_7_DAY }
internal data class MorningReportWindow(val reportDay:LocalDate,val kind:MorningReportKind) {
    val from:Instant get()=reportDay.minusDays(if(kind==MorningReportKind.DAILY)1 else 7).atStartOfDay(KST).toInstant()
    val until:Instant get()=reportDay.atStartOfDay(KST).toInstant()
    val due:Instant get()=reportDay.atTime(6,0).atZone(KST).toInstant()
    fun key(profile:String)=JsonCanonical.digest(listOf("ieum-morning-v1",profile,KST.id,reportDay.toString(),kind.name))
    fun contains(at:Instant)=!at.isBefore(from) && at.isBefore(until)
    companion object {
        val KST:ZoneId=ZoneId.of("Asia/Seoul")
        fun latestDue(now:Instant):LocalDate=now.atZone(KST).let { if(it.hour<6)it.toLocalDate().minusDays(1) else it.toLocalDate() }
        fun nextTarget(now:Instant,hour:Int):Instant=now.atZone(KST).let {
            val at=it.toLocalDate().atTime(hour,0).atZone(KST).toInstant()
            if(now.isBefore(at))at else it.toLocalDate().plusDays(1).atTime(hour,0).atZone(KST).toInstant()
        }
    }
}
internal enum class MorningCoverage { COMPLETE, PARTIAL, UNKNOWN }
/** Facts must already have canonical ACCEPT or current explicit USER_REVIEW_SUMMARY receipts.
 * The port must apply original occurrence time, current visibility/policy and source revisions.
 * COMPLETE means every approved source kind is known covered, including known-empty results. */
internal data class MorningSnapshot(val input:DailyReportInput,val coverage:MorningCoverage,
    val scopeNote:String,val failedCount:Int=0,val connectionRequiredCount:Int=0,val unknownTimeCount:Int=0,val reviewRequiredCount:Int=0) {
    init { require(listOf(failedCount,connectionRequiredCount,unknownTimeCount,reviewRequiredCount).all { it>=0 });require(scopeNote.isNotBlank()) }
    fun validated():MorningSnapshot {
        input.sources.forEach { it.validate() }
        require(input.sources.map { it.id }.distinct().size==input.sources.size)
        require(input.sources.size<=input.expectedCount)
        require(coverage!=MorningCoverage.COMPLETE || (input.pendingCount==0 && failedCount==0 &&
            connectionRequiredCount==0 && unknownTimeCount==0 && reviewRequiredCount==0 && input.unresolved.isEmpty() &&
            (input.fullyReviewed || input.readiness==ReportReadiness.NO_RECORDINGS && input.expectedCount==0)))
        return this
    }
    val fingerprint:String get()=JsonCanonical.digest(listOf(input.basisHash,coverage.name,scopeNote,failedCount,connectionRequiredCount,unknownTimeCount,reviewRequiredCount))
    fun text(window:MorningReportWindow)=buildString {
        append("${window.reportDay} · ${if(window.kind==MorningReportKind.DAILY)"전날" else "최근 7일"} 보고 · KST\n")
        append("조회: ${window.from.atZone(MorningReportWindow.KST)} 이상 ~ ${window.until.atZone(MorningReportWindow.KST)} 미만\n")
        append("입력 범위: ${coverage.name} · $scopeNote\n")
        append("대상 ${input.expectedCount} · 확인 ${input.sources.size} · 대기 ${input.pendingCount} · 실패 $failedCount · 검토 필요 $reviewRequiredCount · 연결 필요 $connectionRequiredCount · 날짜 미확인 $unknownTimeCount\n\n")
        if(input.expectedCount==0)append(if(coverage==MorningCoverage.COMPLETE)"승인된 조회 범위에 업무 데이터 없음\n" else "로컬 자료 없음 · 전체 업무가 없었다고 확정할 수 없음\n")
        else if(input.fullyReviewed)append(input.preview()) else append(input.partialPreview())
        append("\n\n로컬 보관 및 dry-run · 카카오 실제 전송 없음 · 수신/읽음 확인 아님")
    }
}
internal fun interface MorningSnapshotPort { fun snapshot(window:MorningReportWindow):MorningSnapshot }
internal enum class MorningSyncState { ACCEPTED, COMPLETE, DEFERRED, FAILED, UNAVAILABLE }
internal data class MorningSyncRequest(val id:String,val from:Instant,val until:Instant,val durableCursor:String,
    val reason:String,val generation:Long)
internal data class MorningSyncResult(val state:MorningSyncState,val committedCursor:String="",val durable:Boolean=false) {
    init { require(state!=MorningSyncState.COMPLETE || durable && committedCursor.isNotBlank()) }
}
internal fun interface MorningIncrementalSyncPort { suspend fun sync(request:MorningSyncRequest):MorningSyncResult }
internal data class MorningEnvelope(val id:String,val reportKey:String,val revision:Int,val generation:Long,
    val destination:String,val bodyHash:String,val body:String) {
    override fun toString()="MorningEnvelope[$id; content withheld]"
}
internal enum class MorningDeliveryState { OUTBOX_READY, IN_FLIGHT, DRY_RUN_RECORDED, RETRY_WAIT, FAILED, UNKNOWN, ACKNOWLEDGED, CANCELLED }
internal data class MorningDeliveryResult(val state:MorningDeliveryState,val key:String="",val destination:String="",
    val bodyHash:String="",val receipt:String="")
internal fun interface MorningDeliveryPort { suspend fun deliver(envelope:MorningEnvelope):MorningDeliveryResult }
internal object MorningDryRun:MorningDeliveryPort {
    override suspend fun deliver(envelope:MorningEnvelope)=MorningDeliveryResult(MorningDeliveryState.DRY_RUN_RECORDED)
}
internal data class MorningReportRow(val key:String,val window:MorningReportWindow,val generation:Long,val state:String,
    val revision:Int,val fingerprint:String,val attempts:Int,val retryAt:Instant,val observed:Instant?,val note:String)
internal data class MorningOutboxRow(val id:String,val reportKey:String,val revision:Int,val generation:Long,
    val state:MorningDeliveryState,val attempts:Int,val retryAt:Instant,val destination:String,val bodyHash:String,
    val claim:String,val claimBoot:Long,val claimUntil:Long,val receipt:String)
