package kr.callbrief

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.co.callsummary.vault.VaultException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only generated data. Vault/SQLite/key operations execute on this private IO executor. */
open class SyntheticPilotActivity : Activity() {
    @Volatile private var pilot: SyntheticLocalPilot? = null
    private val io = Executors.newSingleThreadExecutor()
    private val disposed = AtomicBoolean()
    private var model: SyntheticLocalPilot.ViewState? = null
    private lateinit var body: LinearLayout
    private var busy = true
    private var message = "암호화 저장소를 확인하고 있어요."
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!BuildConfig.SYNTHETIC_TOOLS_ENABLED) { finish(); return }
        render()
        io.execute {
            try {
                val created = createPilot(); pilot = created
                if (disposed.get()) { created.close(); return@execute }
                val view = created.snapshot()
                runOnUiThread {
                    if (!disposed.get()) {
                        model = view; busy = false
                        message = "별도 합성 원음 저장소예요. 기존 평문 원음은 변경하지 않아요."
                        render()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!disposed.get()) {
                        busy = false
                        message = "암호화 저장소를 열지 못해 보류했어요. 기존 원음과 이력은 유지돼요."
                        render()
                    }
                }
            }
        }
    }
    protected open fun createPilot() = SyntheticLocalPilot(this)
    override fun onDestroy() {
        disposed.set(true)
        pilot?.cancel() // Immediate stream cancellation, without key/DB I/O on the UI thread.
        io.execute { pilot?.close() }
        io.shutdown()
        super.onDestroy()
    }
    private fun text(value: String, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = if (bold) 22f else 18f; setTextColor(Color.rgb(20, 40, 100))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(12, 10, 12, 10)
    }
    private fun failed(error: Exception): String = when ((error as? VaultException)?.code) {
        "KEY_UNAVAILABLE", "KEY_INVALIDATED", "KEY_LOCKED", "KEY_SERVICE_UNAVAILABLE", "KEY_IDENTITY_MISMATCH" ->
            "암호화 키를 사용할 수 없어 분석·승인을 보류했어요. 원음과 이력은 유지돼요."
        "AUTHENTICATION_FAILED", "CORRUPT_STORAGE", "BINDING_MISMATCH", "PLAINTEXT_BINDING_MISMATCH" ->
            "원음 무결성이나 버전 연결을 확인하지 못해 보류했어요. 원음과 이력은 유지돼요."
        "CANCELLED" -> "작업을 취소했어요. 저장된 원음과 이력은 유지돼요."
        else -> "기준이 바뀌었거나 저장하지 못해 보류했어요. 목록을 다시 확인해 주세요."
    }
    private fun submit(operation: (SyntheticLocalPilot) -> String) {
        if (disposed.get() || busy || pilot == null) return
        busy = true; render()
        io.execute {
            if (disposed.get()) return@execute
            val p = pilot ?: return@execute
            var result: String
            try { result = operation(p) }
            catch (error: Exception) { result = failed(error) }
            val next = try { p.snapshot() } catch (_: Exception) { null }
            runOnUiThread {
                if (!disposed.get()) {
                    if (next != null) model = next
                    busy = false; message = result; render()
                }
            }
        }
    }
    private fun action(label: String, enabled: Boolean = true, operation: (SyntheticLocalPilot) -> String) = Button(this).apply {
        text = label; textSize = 18f; minimumHeight = (56 * resources.displayMetrics.density).toInt()
        isAllCaps = false; isEnabled = enabled && !busy && pilot != null
        setOnClickListener { submit(operation) }
    }
    private fun plainButton(label: String, enabled: Boolean = true, operation: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; minimumHeight = (56 * resources.displayMetrics.density).toInt()
        isAllCaps = false; isEnabled = enabled && !busy; setOnClickListener { if (!disposed.get()) operation() }
    }
    private fun render() {
        if (disposed.get() || isFinishing || isDestroyed) return
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); setBackgroundColor(Color.rgb(222, 242, 248))
        }
        setContentView(ScrollView(this).apply {
            tag = "synthetic_pilot_scroll"
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
            addView(body)
            requestApplyInsets()
        })
        body.addView(text("합성 암호화 저장·검토", true))
        body.addView(text("dot 연결 미연결 · 실제 통화·전송 없음", true))
        body.addView(text("앱이 만든 합성 WAV만 별도 암호화 저장소에 보관해요. 실제 폴더는 읽지 않아요. 이 휴대전화의 키·저장·복구 시험은 별도로 필요해요."))
        body.addView(text(message))
        val m = model
        if (m != null) body.addView(text(m.storageText))
        body.addView(action("새 합성 저장소 키 초기화", m?.canInitialize == true) {
            it.initializeEmptyVault(); "새 빈 합성 저장소의 키 초기화를 마쳤어요. 기존 원음은 변경하지 않았어요."
        })
        if(m?.canRecoverInitialization==true) body.addView(action("중단된 첫 키 초기화 이어가기") {
            it.recoverEmptyInitialization(); "빈 저장소와 같은 초기화 요청을 확인했어요. 기존 키가 있으면 교체하지 않았어요."
        })
        body.addView(action("합성 파일 가져오기", m?.keyReady == true) {
            it.collection(); "합성 암호화 원음과 DB 연결을 저장했어요. 분석 결과는 아직 없어요."
        })
        body.addView(action("사전 제외 차단 확인") {
            val before = it.openedSyntheticStreams
            try { it.collection(excluded = true); error("EXCLUSION_CHECK_FAILED") }
            catch (error: RuntimeException) {
                check(error.message.orEmpty().contains("EXCLUDED_BEFORE_READ") && before == it.openedSyntheticStreams)
            }
            "제외 대상은 원음 열기 전에 차단됐어요."
        })
        if (m != null && m.pendingCount > 0) body.addView(action("중단된 합성 저장 확인") {
            val count = it.recoverPendingSynthetic(); "$count 건의 합성 원음 검증과 DB 연결을 확인했어요."
        })
        val current = m?.current
        if (m != null && current != null) {
            val archived = (m.archive["archived"] as Number).toInt() == 1
            body.addView(text(if (archived) "보관한 합성 항목" else "저장된 합성 항목", true))
            body.addView(text("파일 버전 " + current["source_version"] + " · 사용자 수정 " + current["user_revision"] +
                "\n원본 예시 날짜 2026-10-08 · 처리 " + current["state"]))
            val row = m.row!!
            body.addView(text("기준 요약: " + if (row.isNull("summary")) "아직 승인한 요약이 없어요." else row.getString("summary")))
            body.addView(action("암호화 원음 검증") { it.verifyCurrent(); "암호화 원음의 전체 인증 태그와 원문 해시·크기를 확인했어요." })
            if (archived) {
                body.addView(text("기본 목록에서 숨겼어요. 암호화 원음과 모든 버전은 유지돼요."))
                body.addView(action("합성 항목 복원") {
                    it.archive(false, (m.archive["revision"] as Number).toLong()); "합성 항목을 기본 목록으로 되돌렸어요."
                })
            } else {
                body.addView(action("같은 내용 이름 변경") {
                    it.collection("합성 변경 이름.wav"); "이름만 바뀐 동일 내용은 새 원음 버전을 만들지 않았어요."
                })
                body.addView(action("합성 내용 B로 변경") { it.collection(alternate = true); "다른 합성 내용은 새 파일 버전으로 저장했어요." })
                body.addView(action("합성 내용 A로 돌아가기") { it.collection(); "이전 내용으로 돌아가도 새 파일 버전으로 관리해요." })
                body.addView(action("mock 변경안 만들기", m.keyReady && (current["state"] == "PENDING" || current["state"] == "FAILED")) {
                    it.makeMockCandidate()
                    if(it.latestCandidate()?.get("repository_review_code")=="USER_STATE_PRESERVED") "직접 수정·완료를 유지하고 mock 변경안만 검토 대상으로 저장했어요."
                    else "mock 변경안을 저장했어요. 전사·dot 요청이 아니며 아직 요약에 적용하지 않았어요."
                })
                body.addView(plainButton("변경안 검토", m.keyReady && m.candidate != null) { review(m) })
                body.addView(plainButton("직접 수정·완료") { edit(m) })
                body.addView(action("합성 항목 보관") {
                    it.archive(true, (m.archive["revision"] as Number).toLong()); "합성 항목을 기본 목록에서 숨겼어요. 원음을 삭제하지 않았어요."
                })
            }
            body.addView(plainButton("원문·버전 전체 이력") {
                val versions = m.history!!.getJSONArray("versions")
                val details = buildString {
                    for (i in 0 until versions.length()) {
                        val v = versions.getJSONObject(i); append("파일 " + v.getInt("version") + " · 원문 해시 " + v.getString("audio_sha256") + "\n")
                        val results = v.getJSONArray("results")
                        for (j in 0 until results.length()) {
                            val r = results.getJSONObject(j)
                            append("요약 " + r.getInt("revision") + " · 현재 기준 " + r.getBoolean("is_current") + "\n")
                            append(r.getString("raw_text") + "\n" + r.getString("summary") + "\n\n")
                        }
                    }
                }
                AlertDialog.Builder(this).setTitle("합성 원문·버전 이력").setMessage(details).setPositiveButton("닫기", null).create().also { ProtectedDialogWindow.show(it) }
            })
        }
        body.addView(action("다시 읽기") { "기기 SQLite와 암호화 참조를 다시 읽었어요. 앱 프로세스 재시작이나 백업 복구 시험은 아니에요." })
        body.addView(plainButton("4탭으로 돌아가기") { finish() })
    }
    private fun review(m: SyntheticLocalPilot.ViewState) {
        val candidate = m.candidate ?: return
        val reviewed = m.reviewed ?: return
        AlertDialog.Builder(this).setTitle("합성 변경안 · 아직 미적용")
            .setMessage(candidate["summary_text"].toString() +
                "\n\n원음 검증과 기준 비교를 통과한 뒤 기기 요약으로 저장해요. 직접 수정한 문장을 대체할 수 있으므로 확인해 주세요. 할일 완료 상태는 자동 변경하지 않아요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("확인 후 합성 요약 승인") { _, _ ->
                if (!disposed.get()) submit {
                    it.accept((candidate["summary_version"] as Number).toLong(), reviewed)
                    "원음 검증과 기기 저장을 확인했어요. 합성 요약을 직접 승인했어요."
                }
            }.create().also { ProtectedDialogWindow.show(it) }
    }
    private fun edit(m: SyntheticLocalPilot.ViewState) {
        val reviewed = m.reviewed ?: return
        val entry = EditText(this).apply { textSize = 18f; minLines = 3; setText("[합성 직접 수정] 내가 확인한 완료 내용을 보존해요.") }
        AlertDialog.Builder(this).setTitle("합성 문장 직접 수정·완료").setView(entry)
            .setNegativeButton("취소", null).setPositiveButton("합성 수정·완료 저장") { _, _ ->
                val text = entry.text.toString()
                if (!disposed.get()) submit {
                    it.editAndComplete(text, reviewed)
                    "직접 수정·완료를 저장했어요. 이후 mock 변경안은 덮어쓰지 않아요."
                }
            }.create().also { ProtectedDialogWindow.show(it) }
    }
}
