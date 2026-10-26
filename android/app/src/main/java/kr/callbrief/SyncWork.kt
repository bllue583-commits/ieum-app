package kr.callbrief

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.*
import kr.callbrief.core.SeoulTime
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import java.util.concurrent.TimeUnit

object SyncSchedule {
    const val TAG = "callbrief.sync"
    private fun constraints(state: JSONObject): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (state.optBoolean("wifiOnly", true)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()
    fun ensure(context: Context) {
        if (!NativeAnalysisAvailability.canTransfer()) return
        val state = SecureSettings(context).snapshot()
        if (state.optBoolean("pendingRevocation")) { revoke(context); return }
        if (!state.optBoolean("cloudEnabled")) return
        val wm = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints(state)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).build()
        wm.enqueueUniquePeriodicWork("callbrief.periodic", ExistingPeriodicWorkPolicy.UPDATE, periodic)
        scheduleDaily(context,state)
    }
    fun scheduleDaily(context: Context, state: JSONObject) {
        if (!NativeAnalysisAvailability.canTransfer()) return
        if (!state.optBoolean("cloudEnabled") || state.optBoolean("pendingRevocation")) return
        val wm = WorkManager.getInstance(context)
        val now = Instant.now()
        var target = SeoulTime.nextReport(now).minusSeconds(30 * 60)
        if (!target.isAfter(now)) target = target.plusSeconds(24 * 60 * 60)
        val day = target.atZone(SeoulTime.zone).toLocalDate().minusDays(1).toString()
        val daily = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("report_date" to day))
            .setInitialDelay(Duration.between(now, target).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints(state)).addTag(TAG).build()
        wm.enqueueUniqueWork("callbrief.preflight.$day", ExistingWorkPolicy.KEEP, daily)
    }
    fun manual(context: Context) {
        require(NativeAnalysisAvailability.canTransfer()) { NativeAnalysisAvailability.MESSAGE }
        val state = SecureSettings(context).snapshot()
        require(state.optBoolean("cloudEnabled") && !state.optBoolean("pendingRevocation")) { "먼저 본인 동의를 완료해 주세요." }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("report_date" to SeoulTime.yesterday(Instant.now()).toString()))
            .setConstraints(constraints(state)).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("callbrief.manual", ExistingWorkPolicy.KEEP, request)
    }
    fun stop(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
    fun revoke(context: Context) {
        val request = OneTimeWorkRequestBuilder<RevocationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("callbrief.revocation").build()
        WorkManager.getInstance(context).enqueueUniqueWork("callbrief.revocation", ExistingWorkPolicy.KEEP, request)
    }
}

/** No recording permission, microphone, call log, accessibility, or unrestricted storage access. */
class SyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        // Existing legacy server code below never becomes a dot auth shortcut.
        // No recording metadata, bytes, or result is sent while support is unknown.
        if (!NativeAnalysisAvailability.canTransfer()) return Result.failure()
        val settings = SecureSettings(applicationContext)
        var state = settings.snapshot()
        if (!state.optBoolean("cloudEnabled") || state.optBoolean("pendingRevocation")) return Result.success()
        // One-time jobs freeze their intended calendar date. Periodic retries retain their first attempt date.
        val dateKey = "retryDate.$id"
        val date = inputData.getString("report_date") ?: if (runAttemptCount > 0) state.optString(dateKey,
            SeoulTime.yesterday(Instant.now()).toString()) else SeoulTime.yesterday(Instant.now()).toString()
        settings.update { it.put(dateKey, date) }
        try {
            LocalDate.parse(date)
            val api = ServiceApi.from(state)
            val config = api.get("/v1/config")
            if (config.getString("policy_version") != state.optString("policyVersion") ||
                !config.getJSONObject("consents").optBoolean("cloud_upload")) {
                settings.update { it.put("cloudEnabled", false) }
                settings.message("서비스 이용 조건 또는 동의 상태가 바뀌었어요. 초기 설정에서 다시 확인해 주세요.")
                SyncSchedule.stop(applicationContext); return Result.failure()
            }
            val tree = Uri.parse(state.getString("treeUri"))
            val floor = LocalDate.parse(state.getString("firstAllowedDate"))
            val scanner = SafRecordings(applicationContext)
            val scan = scanner.scan(tree)
            val now = Instant.now()
            var uploaded = 0; var skipped = 0; var pending = 0
            val quotaDates=mutableSetOf<String>()
            val limit = minOf(10, config.optInt("max_recordings_per_day", 50))
            val started = System.nanoTime()
            for (raw in scan.recordings) {
                if (isStopped) return Result.retry()
                state = settings.snapshot()
                if (!state.optBoolean("cloudEnabled") || state.optBoolean("pendingRevocation")) return Result.success()
                val override = state.optJSONObject("timeOverrides")?.optString(raw.uri.toString()).orEmpty()
                val recording = if (override.isNotBlank()) raw.copy(startedAt = Instant.parse(override)) else raw
                if (recording.startedAt != null && recording.startedAt.isBefore(SeoulTime.startOfDay(floor))) continue
                if (recording.reason(now) != null || recording.startedAt == null || !SeoulTime.eligible(recording.startedAt, floor, now)) {
                    skipped++; continue
                }
                // Caller identity/privacy check happens BEFORE hashing, opening audio, or metadata transmission.
                if (LocalPrivacy.blocked(recording, state)) { skipped++; continue }
                val identity = LocalPrivacy.identity(recording, state)
                if (identity == null) { skipped++; continue }
                val recordingDate=recording.startedAt!!.atZone(SeoulTime.zone).toLocalDate().toString()
                if(recordingDate in quotaDates) { skipped++;continue }
                if (uploaded >= limit || System.nanoTime() - started > TimeUnit.MINUTES.toNanos(6)) { pending++; continue }
                var fingerprint: String? = null
                try {
                    val ticket = if (config.optBoolean("privacy_preflight_required")) {
                        val preflight = api.preflight(recording, identity)
                        if (!preflight.optBoolean("allowed")) { skipped++; continue }
                        preflight.optString("upload_ticket").takeIf { it.isNotBlank() }
                    } else null
                    val hashed = try { scanner.fingerprint(recording) } catch (_: java.io.IOException) { skipped++; continue }
                    val requestKey = hashed.idempotencyKey; fingerprint=requestKey
                    if (settings.uploadedId(requestKey) != null) continue
                    if (state.optJSONObject("fileIssues")?.has(requestKey) == true) { skipped++; continue }
                    setProgressAsync(workDataOf("uploaded" to uploaded, "skipped" to skipped))
                    settings.update {
                        val attempted = it.optJSONObject("attemptedByUri") ?: JSONObject()
                        attempted.put(recording.uri.toString(),fingerprint); it.put("attemptedByUri",attempted)
                    }
                    val response = api.upload(applicationContext, recording, requestKey, settings.assignment(recording.uri.toString()) ?: LocalPrivacy.reusedCase(recording,state), identity, ticket, hashed.contentSha256)
                    val record = response.optJSONObject("recording") ?: response
                    settings.recordUpload(requestKey, record.getString("id"))
                    settings.update {
                        val uris = it.optJSONObject("uploadedByUri") ?: JSONObject()
                        uris.put(recording.uri.toString(), JSONObject().put("id",record.getString("id")).put("fingerprint",fingerprint)); it.put("uploadedByUri",uris)
                    }; uploaded++
                } catch (e: ApiException) {
                    if (e.status in 400..499) settings.update { it.optJSONObject("attemptedByUri")?.remove(recording.uri.toString()) }
                    if(e.status==429 && e.issue=="daily_audio_limit") {
                        quotaDates.add(recordingDate)
                        settings.update { val issues=it.optJSONObject("fileIssuesByUri") ?: JSONObject();issues.put(recording.uri.toString(),"해당 날짜 처리 한도 · 일부 통화 미업로드, 운영자 확인 필요");it.put("fileIssuesByUri",issues) }
                        skipped++;continue
                    }
                    if (e.status !in listOf(400, 413, 415, 422)) throw e
                    val issue = "파일 거절 ${e.status} · 형식·시각·크기 확인 필요"
                    settings.update {
                        val issues = it.optJSONObject("fileIssues") ?: JSONObject(); if (fingerprint != null) issues.put(fingerprint, issue); it.put("fileIssues", issues)
                        val byUri = it.optJSONObject("fileIssuesByUri") ?: JSONObject(); byUri.put(recording.uri.toString(), issue); it.put("fileIssuesByUri", byUri)
                    }; skipped++
                }
            }
            val reportDate=LocalDate.parse(date)
            val scoped=scan.recordings.filter { raw ->
                val current=settings.snapshot()
                if(LocalPrivacy.blocked(raw,current)) return@filter false
                val override=current.optJSONObject("timeOverrides")?.optString(raw.uri.toString()).orEmpty()
                val time=if (override.isNotBlank()) Instant.parse(override) else raw.startedAt
                time!=null && !time.isBefore(SeoulTime.startOfDay(floor)) && time.atZone(SeoulTime.zone).toLocalDate()==reportDate
            }
            val uploadedForDate=scoped.count { settings.snapshot().optJSONObject("uploadedByUri")?.has(it.uri.toString())==true }
            val scanComplete=!scan.truncated && scoped.size<=500 && scan.recordings.all { raw ->
                val current=settings.snapshot()
                LocalPrivacy.blocked(raw,current) || raw.startedAt!=null ||
                    runCatching { Instant.parse(current.optJSONObject("timeOverrides")?.optString(raw.uri.toString()).orEmpty()) }.isSuccess
            }
            api.json("/v1/sync-status","POST",JSONObject().put("date",date).put("expected_recording_count",if(scanComplete) scoped.size else 0)
                .put("uploaded_recording_count",if(scanComplete) uploadedForDate else 0).put("scan_complete",scanComplete))
            try {
                val digest = api.get("/v1/digests?date=$date")
                settings.update { it.put("lastDigest", digest) }
                val ready = digest.optString("status") in listOf("ready", "complete", "completed") || !digest.isNull("summary")
                if (ready && state.optBoolean("notificationsEnabled")) LocalNotifications.reportReady(applicationContext, date)
            } catch (e: ApiException) { if (e.status != 404) throw e }
            val suffix = if (scan.truncated) " · 폴더 일부만 확인됨 (작은 전용 폴더를 선택해 주세요)" else ""
            settings.update { it.put("lastSyncAt", Instant.now().toString()); it.put("lastSyncError", ""); it.remove(dateKey) }
            settings.message("동기화 완료 · 신규 ${uploaded}개 · 제외 ${scan.excludedCount}개 · 확인/변환 필요 ${skipped}개 · 다음 동기화 대기 ${pending}개$suffix")
            SyncSchedule.scheduleDaily(applicationContext,settings.snapshot())
            return Result.success()
        } catch (e: ApiException) {
            val message = when (e.status) {
                401, 403 -> "연결 인증 또는 동의가 필요해요. 초기 설정을 확인해 주세요."
                413, 415, 422 -> "일부 파일의 크기·형식·통화 시각을 확인해 주세요."
                429 -> "오늘 처리 한도 또는 서비스 제한에 도달했어요. 잠시 후 다시 시도해요."
                else -> "서비스 연결에 실패했어요. 기존 목록은 그대로 볼 수 있어요."
            }
            settings.update { it.put("lastSyncError", message) }; settings.message(message)
            return if (e.status == 429 || e.status >= 500) Result.retry() else Result.failure()
        } catch (_: SecurityException) {
            settings.update { it.put("lastSyncError", "녹음 폴더 읽기 권한을 다시 선택해 주세요.") }
            settings.message("녹음 폴더 읽기 권한을 다시 선택해 주세요."); return Result.failure()
        } catch (_: java.io.IOException) {
            settings.update { it.put("lastSyncError", "인터넷 또는 파일 연결 실패 · 자동 재시도 대기") }
            settings.message("인터넷 또는 파일 연결 실패 · 자동 재시도 대기"); return Result.retry()
        } catch (_: Exception) {
            settings.message("설정이나 통화 시각을 확인해 주세요. 원본 녹음은 변경하지 않았어요.")
            return Result.failure()
        }
    }
}

/** Persistent revocation outbox, separate tag so stopping upload cannot cancel revocation. */
class RevocationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (!NativeAnalysisAvailability.canTransfer()) return Result.failure()
        val settings = SecureSettings(applicationContext)
        val state = settings.snapshot()
        if (!state.optBoolean("pendingRevocation")) return Result.success()
        return try {
            val api = ServiceApi.from(state)
            val config = api.get("/v1/config")
            api.json("/v1/consents", "POST", JSONObject().put("policy_version", config.getString("policy_version"))
                .put("cloud_upload", false).put("external_processing", false).put("kakao_delivery", false))
            settings.update { it.put("pendingRevocation", false); it.put("cloudEnabled", false); it.put("externalEnabled", false); it.put("kakaoEnabled", false) }
            settings.message("서버의 녹음·AI·카카오 전송 중지까지 확인했어요. 기존 자료는 보관 정책에 따라 남아 있어요.")
            Result.success()
        } catch (_: Exception) {
            settings.message("휴대폰 전송은 중지됐어요. 서버 중지는 연결 후 다시 확인해요.")
            Result.retry()
        }
    }
}

object LocalNotifications {
    private const val CHANNEL = "callbrief.ready"
    fun reportReady(context: Context, date: String) {
        if (!NativeAnalysisAvailability.canTransfer()) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val settings = SecureSettings(context)
        if (settings.snapshot().optJSONObject("notifiedDates")?.has(date) == true) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "브리핑 준비", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("report_date",date).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, date.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_callbrief)
            .setContentTitle("통화 브리핑이 준비됐어요").setContentText("앱에서 할 일과 확인이 필요한 내용을 보세요.")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        manager.notify(600, notification)
        settings.update { val dates=it.optJSONObject("notifiedDates") ?: JSONObject();dates.put(date,Instant.now().toString());it.put("notifiedDates",dates) }
    }
}
