package kr.callbrief

import kr.callbrief.core.SeoulTime
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.security.MessageDigest

/** Deterministic fabricated records. No settings, recording folder, backend, or account is read. */
object DemoFixtures {
    val now: Instant = Instant.parse("2026-10-11T00:10:00Z")

    fun board(): Pair<JSONArray, JSONArray> {
        val topics = JSONArray().put(JSONObject().put("id", "demo-case-a").put("counterpart", "예시 고객 A").put("topic", "장비 납품과 설치")
            .put("summary", "[합성 예시] 설치 장소와 준비물을 확인했어요. 납품 날짜는 변경 가능성이 있어 최종 회신을 기다려요.")
            .put("status", "waiting").put("updated_at", "2026-10-10T05:40:00Z").put("recording_count", 3).put("version", 1))
            .put(JSONObject().put("id", "demo-case-b").put("counterpart", "예시 고객 A · 담당 확인 필요").put("topic", "별도 정산 건")
                .put("summary", "[합성 예시] 정산 서류의 대상과 완료 여부를 확인해야 해요. 납품 건과 별개 업무로 남겼어요.")
                .put("status", "needs_confirmation").put("updated_at", "2026-10-10T07:20:00Z").put("recording_count", 1).put("version", 1))
        val tasks = JSONArray()
        val samples = listOf(
            Triple("open", "설치 현장 담당자에게 준비물 목록과 전원 규격을 다시 확인하기", "나"),
            Triple("waiting", "고객이 변경된 납품 날짜를 회신하면 일정표 갱신하기", "예시 고객 A"),
            Triple("needs_confirmation", "정산 계약서 수정본을 보냈다는 말의 대상과 완료 여부 확인하기", "확인 필요"),
            Triple("in_progress", "부품 수량을 대조하고 부족한 항목을 구매 담당에게 전달하기", "나"),
            Triple("done", "설치 장소 주소와 출입 안내를 전달하기", "나")
        )
        samples.forEachIndexed { index, (status, title, owner) ->
            val fileId = when (index) { 2 -> "demo-file-b"; 4 -> "demo-file-c"; else -> "demo-file-a" }
            tasks.put(JSONObject().put("id", "demo-task-$index").put("case_id", if (index == 2) "demo-case-b" else "demo-case-a")
                .put("title", title).put("assignee", owner).put("due_date", if (index == 2) JSONObject.NULL else "2026-10-12")
                .put("status", status).put("evidence_recording_id", fileId).put("evidence_quote", "[합성 예시] 날짜를 다시 확인해서 알려드릴게요.")
                .put("version", 1).put("manual_fields", JSONArray()).put("history", JSONArray().put(JSONObject()
                    .put("at", "2026-10-10T07:30:00Z").put("source", "ai").put("recording_id", fileId)
                    .put("changes", JSONObject().put("status", JSONObject().put("from", JSONObject.NULL).put("to", status)))))
            )
        }
        return topics to tasks
    }

    fun catalogFiles(): JSONArray = JSONArray()
        .put(file("demo-file-a", 2, "2026-10-10T01:00:00Z", "2026-10-10", "demo-counterpart-a", "demo-case-a", "processed", "present", "2026-10-10T05:35:00Z",
            "[합성 예시] 준비물 확인이 추가됐어요. 납품 날짜는 회신 대기이며 완료로 확정하지 않았어요.", 2))
        .put(file("demo-file-b", 1, "2026-10-10T07:00:00Z", "2026-10-10", "demo-counterpart-a", "demo-case-b", "processed", "present", "2026-10-10T07:05:00Z",
            "[합성 예시] 별도 정산 서류의 대상과 발송 완료 여부가 불명확해 확인이 필요해요.", 1))
        .put(file("demo-file-c", 1, "2026-10-09T02:00:00Z", "2026-10-09", "demo-counterpart-a", "demo-case-a", "processed", "missing", "2026-10-10T00:20:00Z",
            "[합성 예시] 설치 장소 주소와 출입 안내를 전달했어요. 이후 준비물 확인은 별도 할일이에요.", 1))
        .put(file("demo-file-d", 1, null, null, "demo-counterpart-unknown", null, "unknown_date", "present", "2026-10-10T08:00:00Z", null, 0))
        .put(file("demo-file-e", 1, "2026-10-08T03:00:00Z", "2026-10-08", "demo-counterpart-a", "demo-case-a", "unsupported", "present", "2026-10-10T08:05:00Z", null, 0))

    private fun file(id: String, version: Int, started: String?, date: String?, counterpart: String, caseId: String?, status: String, presence: String, collected: String, summary: String?, revision: Int): JSONObject = JSONObject()
        .put("file_id", id).put("source_version", version).put("original_started_at", started ?: JSONObject.NULL).put("original_date", date ?: JSONObject.NULL)
        .put("counterpart_ref", counterpart).put("case_id", caseId ?: JSONObject.NULL).put("status", status).put("presence", presence)
        .put("collected_at", collected).put("summary", summary ?: JSONObject.NULL).put("summary_revision", revision).put("synthetic", true)

    fun history(fileId: String): JSONObject {
        val rows = catalogFiles()
        val file = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("file_id") == fileId }
            ?: return JSONObject().put("file_id", fileId).put("versions", JSONArray()).put("synthetic", true)
        val versions = JSONArray()
        if (fileId == "demo-file-a") {
            versions.put(version(1, "2026-10-10T01:05:00Z", "processed", JSONArray().put(result(1,
                "[합성 원문 1] 납품은 다음 주를 생각하고 있어요. 날짜는 다시 확인해 주세요.",
                "[합성 예시] 납품 날짜를 다시 확인해야 해요. 준비물 추가 요청은 아직 없어요.", "2026-10-10T01:10:00Z")), fileId))
            versions.put(version(2, "2026-10-10T05:35:00Z", "processed", JSONArray().put(result(2,
                "[합성 원문 2] 준비물과 전원 규격도 다시 확인해 주세요. 납품 날짜는 아직 확정하지 않았어요.",
                file.getString("summary"), "2026-10-10T05:40:00Z")), fileId))
        } else {
            val results = JSONArray()
            if (!file.isNull("summary")) results.put(result(1,
                if (fileId == "demo-file-b") "[합성 원문] 서류를 보냈다는 말이 어떤 계약서인지 다시 확인해 주세요."
                else "[합성 원문] 설치 장소 주소와 출입 안내를 전달했어요.", file.getString("summary"), file.getString("collected_at")))
            versions.put(version(1, file.getString("collected_at"), file.getString("status"), results, fileId))
        }
        return JSONObject().put("file_id", fileId).put("versions", versions).put("synthetic", true)
    }

    private fun version(number: Int, collected: String, status: String, results: JSONArray, fileId: String): JSONObject = JSONObject()
        .put("version", number).put("audio_sha256", digestText("synthetic-audio:$fileId:$number"))
        .put("collected_at", collected).put("status", status).put("results", results)

    private fun result(revision: Int, raw: String?, summary: String, captured: String): JSONObject = JSONObject()
        .put("revision", revision).put("raw_text", raw ?: JSONObject.NULL).put("summary", summary)
        .put("raw_text_sha256", raw?.let { digestText(it) } ?: JSONObject.NULL).put("summary_sha256", digestText(summary)).put("captured_at", captured)

    private fun digestText(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun review(at: Instant = now): JSONObject {
        val date = SeoulTime.yesterday(at).toString()
        val rows = catalogFiles()
        val changes = JSONArray()
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
            Instant.parse(it.getString("collected_at")).atZone(SeoulTime.zone).toLocalDate().toString() == date
        }.forEach { row -> changes.put(JSONObject().put("file_id", row.getString("file_id"))
            .put("original_date", row.get("original_date")).put("source_version", row.getInt("source_version"))
            .put("description", if (row.getString("file_id") == "demo-file-a") "[합성 예시] 파일 2차와 요약 2차가 저장됐어요."
                else "[합성 예시] 어제 수집한 기록이에요. 원본 통화 날짜는 별도로 유지해요.")) }
        val checks = JSONArray()
        if (changes.length() > 0) checks.put(JSONObject().put("file_id", "demo-file-d").put("reason", "[합성 예시] 통화 날짜를 알 수 없어 직접 확인해야 해요."))
            .put(JSONObject().put("file_id", "demo-file-b").put("reason", "[합성 예시] 정산 서류 대상과 완료 여부를 확인해야 해요."))
            .put(JSONObject().put("file_id", "demo-file-e").put("reason", "[합성 예시] 지원하지 않는 파일 형식이에요. 요약 완료로 처리하지 않아요."))
        return JSONObject().put("date", date).put("file_changes", changes).put("review_required", checks).put("applied", false).put("synthetic", true)
    }

    fun recording(): JSONObject = JSONObject().put("started_at", "2026-10-10T01:00:00Z").put("status", "processed")
        .put("summary", "[합성 예시] 일정 변경 가능성이 있어 완료 여부를 확인해야 해요.").put("synthetic", true)

    fun digest(date: String): JSONObject = JSONObject().put("date", date).put("status", "ready").put("synthetic", true).put("delivery_status", "전송하지 않음")
        .put("summary", "[합성 예시] 납품 날짜 회신 대기, 설치 준비물 확인, 별도 정산 서류 완료 여부 확인이 필요해요. 실제 통화 내용이나 카카오 전송 결과가 아니에요.")
}
