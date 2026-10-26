package kr.callbrief

import android.app.AlertDialog
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Local reports and explicit day-level review. No account login or send approval is performed here. */
class DailyReportActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private lateinit var scope:DailyReportRuntime.Scope
    private var busy=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        scope=try { DailyReportRuntime.scope(this) } catch(_:Exception) {
            setContentView(TextView(this).apply { text="계정 저장 정보를 읽지 못했어요. 기존 보고·키·원본은 유지했어요.";textSize=18f })
            return
        }
        val scroll=ScrollView(this);body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            val padding=(16*resources.displayMetrics.density).toInt()
            view.setPadding(padding,insets.systemWindowInsetTop+padding,padding,insets.systemWindowInsetBottom+padding);insets
        }
        scroll.addView(body);setContentView(scroll);refresh()
    }
    private fun label(value:String)=TextView(this).apply { text=value;textSize=18f;setPadding(0,12,0,12) }
    private fun button(value:String,action:()->Unit)=Button(this).apply { text=value;textSize=18f;minHeight=(56*resources.displayMetrics.density).toInt();setOnClickListener { if(!busy)action() } }
    override fun onResume() { super.onResume();if(::scope.isInitialized && !DailyReportRuntime.current(this,scope))finish() }
    private fun coordinator(ledger:DailyReportLedger)=DailyReportCoordinator(ledger,CanonicalReviewedDailySource(scope.context),
        KakaoDailyReportSender(scope.context),{ DailyReportRuntime.environment(this,"LOCAL_REPORT_SCREEN") },{ DailyReportRuntime.current(this,scope) })
    private fun refresh() {
        if(busy)return;busy=true
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                check(DailyReportRuntime.current(this@DailyReportActivity,scope))
                DailyReportLedger(scope.context,scope.profile).use { ledger ->
                    val coordinator=coordinator(ledger);coordinator.tick();val reports=ledger.list()
                    Triple(ledger.config(),reports,reports.associate { it.id to ledger.partialHistory(it.id) }) to
                        AutomationLocalReportReader.read(scope.context,(scope.context as? AccountStorageContext)?.ownerId.orEmpty())
                }
            } }
            busy=false;if(isFinishing || isDestroyed)return@launch
            if(!DailyReportRuntime.current(this@DailyReportActivity,scope)) { finish();return@launch }
            body.removeAllViews();body.addView(label("매일 오전 6시 보고 · 보관과 실행 상태").apply { textSize=24f })
            body.addView(label("기기 시간대 ${ZoneId.systemDefault().id}의 오전 6시가 목표예요. Doze·절전·앱 중지로 늦어질 수 있어요. 정각 실행은 보장하지 않아요."))
            body.addView(label("검증 정책을 통과한 자동 누적 내용과 미완료 항목은 로컬 보고로 보관해요. 기존 직접 확인 보고는 유지해요. Kakao 무인 발송은 정책 미확인으로 비활성이고 사용자 직접 보내기 인터페이스만 유지해요."))
            body.addView(button("KST 전날·최근 7일 보고 / 동기화 상태") { showMorningReports() }.apply { tag="morning_reports" })
            body.addView(button("날짜별 통화·부분 내용 보기") { choosePartialDate() }.apply { tag="daily_partial_date" })
            body.addView(label("시간대가 바뀌면 이전 보고의 UTC 종료 시각부터 다음 조회를 이어가요. 표시 날짜와 조회 구간이 다를 수 있어요."))
            body.addView(label("하루를 넘는 밀린 구간은 실제 시작·끝을 표시해 검토 대기로 보관해요. 전날 보고에 섞거나 자동으로 보내지 않아요."))
            body.addView(label("전송 임대 120초는 자동 시간 제한이 아니에요. 다음 작업 실행이나 상태 확인 때 만료를 확인해 결과 불확실로 보관해요."))
            result.onFailure { body.addView(label("보고 상태를 읽지 못했어요. 저장된 보고·키·원본은 바꾸지 않았어요.")) }
            result.onSuccess { (existing,localCopies) ->
                val(config,reports,partials)=existing
                localCopies.forEach{(title,text)->body.addView(label(title));body.addView(label(text))}
                if(config.legacyHold)body.addView(label("이전 v1 보고가 있어요. 기존 보고를 보존하고 일일보고 시작을 보류해요. 이관 전 별도 검토가 필요해요."))
                if(!config.legacyHold)body.addView(button(if(config.enabled)"일일보고 보관 예약 중단" else "일일보고 보관 예약 사용") {
                    busy=true;lifecycleScope.launch {
                        val saved=runCatching { withContext(Dispatchers.IO) {
                            check(DailyReportRuntime.current(this@DailyReportActivity,scope))
                            DailyReportLedger(scope.context,scope.profile).use { it.setEnabled(!config.enabled,Instant.now(),ZoneId.systemDefault()) }
                            DailyReportSchedule.changed(this@DailyReportActivity)
                        } }
                        busy=false;saved.onFailure { Toast.makeText(this@DailyReportActivity,"설정 저장을 확인하지 못했어요.",Toast.LENGTH_LONG).show() };refresh()
                    }
                })
                body.addView(label(if(config.enabled)"예약 코드 등록 요청됨 · 실제 기기 실행은 확인 필요" else "예약 꺼짐 · 카카오 동의와 별도 설정"))
                if(reports.isEmpty())body.addView(label("아직 보관된 일일보고가 없어요."))
                reports.forEach { r ->
                    body.addView(label("${r.window.titleKo()} · ${r.window.zone.id}\n${r.stateKo()}\n${r.note}"))
                    body.addView(label(r.window.rangeKo()))
                    val late=r.observed?.let { java.time.Duration.between(r.window.due,it).seconds.coerceAtLeast(0) }
                    body.addView(label("목표: ${r.window.due.atZone(r.window.zone)}\n실행 확인: ${r.observed?.atZone(r.window.zone) ?: "대기"}"+
                        (late?.let { "\n목표 이후 ${it/60}분 지연" } ?: "")))
                    if(r.retryAt!=Instant.EPOCH)body.addView(label("재시도 가능 시각: ${r.retryAt.atZone(r.window.zone)}"))
                    if(r.basisHash.isNotBlank())body.addView(button("앱에 보관된 보고 보기") { showStored(r) })
                    partials[r.id]?.forEach { (key,at) ->body.addView(button("부분 보고 이력 · ${at.atZone(r.window.zone)}") { showPartial(r,key) }) }
                    if(config.enabled && !r.window.backlogReview && r.basisHash.isBlank() && r.readiness==ReportReadiness.REVIEWED)
                        body.addView(button("검토된 결과로 보고 내용 확인") { review(r) })
                    if(r.readiness==ReportReadiness.STALE_REVIEW)body.addView(label("이전 보고는 유지해요. 변경된 내용을 별도 수정판으로 보내는 흐름은 아직 연결되지 않았어요."))
                }
            }
            body.addView(button("상태 다시 확인") { refresh() });body.addView(button("설정으로 돌아가기") { finish() })
        }
    }
    private fun showMorningReports() {
        busy=true
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                check(DailyReportRuntime.current(this@DailyReportActivity,scope))
                DailyReportLedger(scope.context,scope.profile).use { ledger ->
                    val config=ledger.config()
                    MorningReports.run(this@DailyReportActivity,scope,ledger,DailyReportRuntime.environment(this@DailyReportActivity,"MANUAL_STATUS"),config.generation,
                        { DailyReportRuntime.current(this@DailyReportActivity,scope) })
                    val repository=ledger.morning();val outbox=repository.outbox()
                    Triple(config,"${repository.syncStatus()} / 마지막 저장 완료: ${repository.lastSuccessfulSync() ?: "미확인"}",repository.reports().take(60).map { row ->
                        val visible=runCatching { CanonicalMorningSnapshotPort(scope.context).snapshot(row.window).validated().fingerprint==row.fingerprint }.getOrDefault(false)
                        val text=if(row.revision==0)"보관된 본문 없음" else if(visible)repository.body(row.key,row.revision)
                            else "현재 원본·권한·검토 상태가 달라 본문을 숨겼어요. 보관본은 유지해요."
                        Triple(row,text,outbox.filter { it.reportKey==row.key }.joinToString(" / ") { "r${it.revision}: ${it.state.name}" })
                    })
                }
            } }
            busy=false
            if(isFinishing || isDestroyed || !DailyReportRuntime.current(this@DailyReportActivity,scope))return@launch
            val content=LinearLayout(this@DailyReportActivity).apply { orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16) }
            content.addView(label("KST 23시 동기화 · 06시 전날/최근 7일 보고\n정각 실행은 보장되지 않아요. 절전·재부팅·강제 중지 이후 OS가 허용하면 복구해요.\n카카오 실제 발송 없음 · dry-run은 수신 확인이 아니에요."))
            result.onFailure { content.addView(label("보고 저장소를 읽지 못했어요. 기존 자료는 보존해요.")) }
            val dialog=AlertDialog.Builder(this@DailyReportActivity).setTitle("KST 보고 실행 상태")
                .setView(ScrollView(this@DailyReportActivity).apply { addView(content) }).setPositiveButton("닫기",null).create()
            result.onSuccess { (config,sync,rows) ->
                content.addView(label("동기화: $sync · SMS/네이버 전체 연동은 미완료\n예약: ${if(config.enabled)"사용" else "중단"}"))
                if(rows.isEmpty())content.addView(label("아직 KST 보고가 없어요. 일일보고 예약을 사용하면 가장 최근 도래한 대상일부터 보관해요."))
                rows.forEach { (row,text,delivery) ->
                    content.addView(label("${row.window.reportDay} ${row.window.kind} · r${row.revision}\n${row.state} · ${row.note}\n목표 ${row.window.due} / 보관 ${row.observed ?: "대기"}\n$delivery\n$text"))
                    if(config.enabled && config.generation==row.generation && row.state!="CANCELLED")content.addView(button("현재 자료로 다시 조회 / 알려진 실패 재시도") {
                        dialog.dismiss();busy=true
                        lifecycleScope.launch {
                            val retried=runCatching { withContext(Dispatchers.IO) {
                                check(DailyReportRuntime.current(this@DailyReportActivity,scope))
                                DailyReportLedger(scope.context,scope.profile).use { it.morning { DailyReportRuntime.current(this@DailyReportActivity,scope) }.manualRetry(row.key,config.generation) }
                            } }
                            busy=false
                            retried.onFailure { Toast.makeText(this@DailyReportActivity,"현재 예약·계정 상태를 확인해 주세요.",Toast.LENGTH_LONG).show() }
                            showMorningReports()
                        }
                    })
                }
            }
            ProtectedDialogWindow.show(dialog)
        }
    }
    private fun choosePartialDate() {
        val date=Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        android.app.DatePickerDialog(this,{_,y,m,d ->
            val window=ReportWindow(java.time.LocalDate.of(y,m+1,d),ZoneId.systemDefault())
            busy=true;lifecycleScope.launch {
                val input=runCatching { withContext(Dispatchers.IO) {
                    check(DailyReportRuntime.current(this@DailyReportActivity,scope));CanonicalReviewedDailySource(scope.context).snapshot(window)
                } }
                busy=false;if(isFinishing || isDestroyed || !DailyReportRuntime.current(this@DailyReportActivity,scope))return@launch
                val value=input.getOrNull()
                val text=when { value==null->"목록 조회 실패 · 대상 수 미확정. 이전 보고는 유지해요."
                    value.fullyReviewed->"직접 확인한 내용 · 최종 보고 저장 전\n\n"+value.preview()
                    else->value.partialPreview() }
                val dialog=AlertDialog.Builder(this@DailyReportActivity).setTitle(window.titleKo()+" · 현재 조회")
                    .setMessage(window.rangeKo()+"\n\n"+text).setPositiveButton("닫기",null).create();ProtectedDialogWindow.show(dialog)
            }
        },date.year,date.monthValue-1,date.dayOfMonth).show()
    }
    private fun showPartial(r:SavedDailyReport,key:String) {
        busy=true;lifecycleScope.launch {
            val text=runCatching { withContext(Dispatchers.IO) { check(DailyReportRuntime.current(this@DailyReportActivity,scope))
                DailyReportLedger(scope.context,scope.profile).use { it.partialText(r.id,key) } } }
            busy=false;if(isFinishing || isDestroyed || !DailyReportRuntime.current(this@DailyReportActivity,scope))return@launch
            val dialog=AlertDialog.Builder(this@DailyReportActivity).setTitle(if(text.isSuccess)"앱에 보관된 부분 보고 이력 · 현재 상태는 다시 조회해 주세요" else "부분 보고 읽기 확인 필요")
                .setMessage(text.getOrDefault("부분 보고 저장소·키 확인 필요 · 기존 보고 유지")).setPositiveButton("닫기",null).create();ProtectedDialogWindow.show(dialog)
        }
    }
    private fun showStored(r:SavedDailyReport) {
        busy=true;lifecycleScope.launch {
            val text=runCatching { withContext(Dispatchers.IO) { check(DailyReportRuntime.current(this@DailyReportActivity,scope))
                DailyReportLedger(scope.context,scope.profile).use { it.text(r.id) } } }
            busy=false;if(isFinishing || isDestroyed)return@launch
            if(!DailyReportRuntime.current(this@DailyReportActivity,scope)) { finish();return@launch }
            val dialog=AlertDialog.Builder(this@DailyReportActivity).setTitle(r.window.titleKo()+" · 앱 보관")
                .setMessage(r.window.bodyWithRange(text.getOrDefault("보고 저장소·키 확인 필요 · 기존 보고 유지"))).setPositiveButton("닫기",null).create()
            ProtectedDialogWindow.show(dialog)
        }
    }
    private fun review(r:SavedDailyReport) {
        busy=true;lifecycleScope.launch {
            val input=runCatching { withContext(Dispatchers.IO) { CanonicalReviewedDailySource(scope.context).snapshot(r.window) } }.getOrNull()
            busy=false;if(r.window.backlogReview || input?.fullyReviewed!=true || !DailyReportRuntime.current(this@DailyReportActivity,scope)) { refresh();return@launch }
            DailyReportReviewDialog.show(this@DailyReportActivity,r.window,input.preview()) { text ->
                if(isFinishing || isDestroyed || !DailyReportRuntime.current(this@DailyReportActivity,scope))return@show
                busy=true
                lifecycleScope.launch {
                    val saved=runCatching { withContext(Dispatchers.IO) {
                        DailyReportLedger(scope.context,scope.profile).use { coordinator(it).review(r.id,input.basisHash,text,true) }
                    } };busy=false
                    saved.onFailure { Toast.makeText(this@DailyReportActivity,"기록·검토 상태가 바뀌었거나 저장을 확인하지 못했어요.",Toast.LENGTH_LONG).show() }
                    refresh()
                }
            }
        }
    }
}
