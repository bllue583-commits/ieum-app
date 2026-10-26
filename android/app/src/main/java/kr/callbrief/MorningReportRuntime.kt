package kr.callbrief

import android.content.Context
import kotlinx.coroutines.CancellationException

/** Adapter over the current read-only, receipt-checking source. SMS/Naver production adapters can
 * implement these ports without giving the report module account tokens or source write access. */
internal class CanonicalMorningSnapshotPort(private val source:ReviewedDailySource):MorningSnapshotPort {
    constructor(context:Context):this(CanonicalReviewedDailySource(context))
    override fun snapshot(window:MorningReportWindow):MorningSnapshot {
        val input=source.snapshot(ReportWindow(window.reportDay.minusDays(1),MorningReportWindow.KST,window.from,window.until))
        return MorningSnapshot(input,if(input.readiness==ReportReadiness.SOURCE_FAILURE)MorningCoverage.UNKNOWN else MorningCoverage.PARTIAL,
            "로컬 통화 검토 자료만 조회 · SMS/네이버 전체 수집·동기화 범위 미확인",
            failedCount=input.unresolved.count { it.reason in setOf("ANALYSIS_FAILED","CURRENT_REQUEST_FAILED") },
            unknownTimeCount=input.unresolved.count { !it.originalTimeKnown },
            reviewRequiredCount=input.unresolved.count { it.reason in setOf("RESULT_REVIEW_PENDING","MANUAL_REVIEW_REQUIRED","IDENTITY_TIME_UNCONFIRMED") })
    }
}
internal object UnavailableMorningSync:MorningIncrementalSyncPort {
    override suspend fun sync(request:MorningSyncRequest)=MorningSyncResult(MorningSyncState.UNAVAILABLE)
}

internal class MorningReportCoordinator(private val repository:MorningReportRepository,
    private val snapshotPort:MorningSnapshotPort,private val syncPort:MorningIncrementalSyncPort,
    private val deliveryPort:MorningDeliveryPort=MorningDryRun,private val environment:()->ReportEnvironment,
    private val current:()->Boolean,private val deliveryNeedsNetwork:Boolean=false) {
    suspend fun tick(generation:Long) {
        if(!current())return
        val token=repository.lease(generation,environment()) ?: return
        fun active()=current() && repository.valid(generation,token,environment())
        try {
            val env=environment();val local=env.now.atZone(MorningReportWindow.KST)
            val primaryDay=if(local.hour>=23)local.toLocalDate() else local.toLocalDate().minusDays(1)
            val day=MorningReportWindow.latestDue(env.now)
            val slots=listOf(Triple(primaryDay.toString(),"23H_PRIMARY",primaryDay.minusDays(6)),
                Triple(day.toString(),"PRE_REPORT",day.minusDays(7)),
                Triple((env.now.epochSecond/900).toString(),"LATE_RESULT",day.minusDays(7)))
            for((slot,reason,from) in slots) {
                if(!active())return
                val syncFrom=minOf(from,repository.cursor().minusDays(7))
                val request=repository.syncRequest(slot,reason,syncFrom.atStartOfDay(MorningReportWindow.KST).toInstant(),env.now,generation,token,environment()) ?: continue
                val result=if(!env.online || env.doze || !env.batteryOkay)MorningSyncResult(MorningSyncState.DEFERRED)
                    else try { syncPort.sync(request) }
                    catch(cancelled:CancellationException) { throw cancelled }
                    catch(_:Exception) { MorningSyncResult(MorningSyncState.FAILED) }
                if(!active())return
                repository.syncComplete(request,result,generation,token,environment())
                // A pending asynchronous request is reconciled under its original ID on a later run.
                if(result.state in setOf(MorningSyncState.ACCEPTED,MorningSyncState.DEFERRED,MorningSyncState.FAILED))break
            }
            fun visible(snapshot:MorningSnapshot):MorningSnapshot =
                if(snapshot.coverage==MorningCoverage.COMPLETE && (!environment().online || repository.syncStatus()!="COMPLETE"))
                    snapshot.copy(coverage=MorningCoverage.PARTIAL,scopeNote=snapshot.scopeNote+" · 최신 동기화 미확인") else snapshot
            fun build(window:MorningReportWindow):Boolean {
                if(!active() || environment().now.isBefore(window.due))return false
                val row=repository.allocate(window,generation,token,environment())
                if(row.state=="CANCELLED" || row.generation!=generation)return true
                if(!repository.buildDue(row,environment()))return row.state.startsWith("GENERATED_")
                val snapshot=try { snapshotPort.snapshot(window).validated() }
                    catch(cancelled:CancellationException) { throw cancelled }
                    catch(_:IllegalArgumentException) { repository.fail(row,generation,token,environment(),true);return false }
                    catch(_:Exception) { repository.fail(row,generation,token,environment(),false);return false }
                if(!active())return false
                // Offline/unfinished synchronization cannot prove fresh comprehensive coverage.
                return try { repository.commit(row,visible(snapshot),generation,token,environment());true }
                    catch(_:IllegalArgumentException) { repository.fail(row,generation,token,environment(),true);false }
            }
            var cursor=repository.cursor()
            repeat(7) {
                if(cursor>day || !active())return@repeat
                val complete=MorningReportKind.entries.map { build(MorningReportWindow(cursor,it)) }.all { it }
                if(!complete)return@repeat
                cursor=cursor.plusDays(1);repository.advance(cursor,generation,token,environment())
            }
            // Late data updates original report dates, never today's occurrence dates. Bounded refresh.
            repository.reports().filter { (it.window.reportDay>=day.minusDays(6) || it.note=="MANUAL_RETRY") && it.window.reportDay<=day && it.window.reportDay<cursor }
                .forEach { build(it.window) }
            for(row in repository.outbox()) {
                if(!active())return
                val currentEnv=environment()
                if(deliveryNeedsNetwork && (!currentEnv.online || currentEnv.doze || !currentEnv.batteryOkay))continue
                val claimed=repository.claim(row,generation,token,currentEnv) ?: continue
                val report=requireNotNull(repository.get(claimed.reportKey))
                val fresh=try { visible(snapshotPort.snapshot(report.window).validated()) }
                    catch(cancelled:CancellationException) { throw cancelled }
                    catch(_:Exception) { null }
                if(!active())return
                if(fresh==null || fresh.fingerprint!=report.fingerprint) {
                    repository.complete(claimed,MorningDeliveryResult(MorningDeliveryState.CANCELLED),generation,token,environment());continue
                }
                val body=try { repository.body(claimed.reportKey,claimed.revision) }
                    catch(_:Exception) { repository.complete(claimed,MorningDeliveryResult(MorningDeliveryState.FAILED),generation,token,environment());continue }
                if(!active())return
                val result=try {
                    deliveryPort.deliver(MorningEnvelope(claimed.id,claimed.reportKey,claimed.revision,generation,claimed.destination,claimed.bodyHash,body))
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { MorningDeliveryResult(MorningDeliveryState.UNKNOWN) }
                if(active())repository.complete(claimed,result,generation,token,environment())
            }
        } finally { repository.release(token) }
    }
}

/** Hard-wired local-only adapters. No account SDK, OAuth, remote sender or permission request. */
internal object MorningReports {
    suspend fun run(context:Context,scope:DailyReportRuntime.Scope,ledger:DailyReportLedger,env:ReportEnvironment,generation:Long,current:()->Boolean) {
        MorningReportCoordinator(ledger.morning(current),CanonicalMorningSnapshotPort(scope.context),UnavailableMorningSync,
            environment={ DailyReportRuntime.environment(context,env.trigger) },current=current).tick(generation)
    }
}
