package kr.callbrief

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kr.callbrief.core.AudioPolicy
import kr.callbrief.core.RecordingNames
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

data class RecordingRef(val uri: Uri, val name: String, val size: Long, val mime: String?, val startedAt: Instant?, val modifiedAt: Long) {
    fun reason(now: Instant): String? = when {
        mime == null -> "지원하지 않는 오디오 형식 (변환 필요)"
        !AudioPolicy.safeSize(size) -> "파일 크기 미확인 또는 25 MB 초과"
        startedAt == null -> "통화 시각 미확인: 파일명 시각 또는 직접 지정 필요"
        modifiedAt <= 0 -> "저장 완료 시각을 확인할 수 없어 수동 확인 필요"
        modifiedAt > now.minusSeconds(120).toEpochMilli() -> "아직 저장 중일 수 있어 대기"
        else -> null
    }
}
data class AudioFingerprint(val idempotencyKey: String, val contentSha256: String)
data class RecordingScan(val recordings: List<RecordingRef>, val truncated: Boolean, val excludedCount: Int)

class SafRecordings(private val context: Context) {
    fun scan(tree: Uri): RecordingScan {
        require(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { "녹음 폴더 읽기 권한을 다시 선택해 주세요." }
        val queue = java.util.ArrayDeque<Pair<String, Int>>()
        queue.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        val result = mutableListOf<RecordingRef>(); var visited = 0; var truncated = false; var excludedCount = 0; val metadataConflicts=mutableSetOf<String>()
        val state = SecureSettings(context).snapshot(); val rules = LocalPrivacy.rules(state)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        while (queue.isNotEmpty()) {
            val (parent, depth) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (++visited > 2000) { truncated = true; break }
                    val id = cursor.getString(0); val name = cursor.getString(1) ?: ""
                    val providedMime = cursor.getString(2) ?: ""
                    if (providedMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 4) queue.add(id to depth + 1) else truncated = true
                    } else if (providedMime.startsWith("audio/") || AudioPolicy.mime(name) != null ||
                        AudioPolicy.extension(name) in listOf("amr", "3gp", "aac")) {
                        // Examine metadata only. Excluded filenames/URIs never enter returned or persisted lists.
                        if (rules.excluded(name)) {
                            excludedCount++
                            // Disable an existing alias reuse if an excluded/changed phone appears. Store only the existing mapping key/flag.
                            val key=LocalPrivacy.mappingKeyFromName(name,state)
                            if(key!=null && state.optJSONObject("aliasMappings")?.has(key)==true) metadataConflicts.add(key)
                            continue
                        }
                        val ref = RecordingRef(DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                            if (cursor.isNull(3)) -1 else cursor.getLong(3), AudioPolicy.mime(name), RecordingNames.startedAt(name),
                            if (cursor.isNull(4)) 0 else cursor.getLong(4))
                        if (LocalPrivacy.blocked(ref, state)) { excludedCount++; continue }
                        result.add(ref)
                    }
                }
            } ?: throw IOException("선택한 폴더를 열 수 없어요.")
            if (visited > 2000) break
        }
        // New explicit number evidence that contradicts a reused alias disables automatic reuse.
        val conflicts=metadataConflicts
        for(ref in result) {
            val key=LocalPrivacy.mappingKey(ref,state) ?: continue
            val mapping=state.optJSONObject("aliasMappings")?.optJSONObject(key) ?: continue
            val phones=kr.callbrief.core.PrivacyRules.phoneTokens(ref.name)
            if(phones.isNotEmpty() && (phones.size!=1 || phones.single()!=mapping.optString("phone"))) conflicts.add(key)
        }
        if(conflicts.isNotEmpty()) SecureSettings(context).update { current -> for(key in conflicts) current.optJSONObject("aliasMappings")?.optJSONObject(key)?.put("conflict",true) }
        return RecordingScan(result.sortedBy { it.startedAt ?: Instant.MAX }, truncated, excludedCount)
    }
    fun fingerprint(recording: RecordingRef): AudioFingerprint {
        LocalPrivacy.requireAllowed(recording, SecureSettings(context).snapshot())
        val digest = MessageDigest.getInstance("SHA-256")
        val contentDigest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(recording.uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                if (total > AudioPolicy.MAX_BYTES || total > recording.size) throw IOException("녹음 파일이 바뀌었어요.")
                digest.update(buffer, 0, n); contentDigest.update(buffer, 0, n)
            }
        } ?: throw IOException("녹음 파일을 읽을 수 없어요.")
        if (total != recording.size) throw IOException("녹음 파일 크기가 바뀌었어요.")
        digest.update(recording.startedAt.toString().toByteArray(Charsets.UTF_8))
        return AudioFingerprint(digest.digest().joinToString("") { "%02x".format(it) }, contentDigest.digest().joinToString("") { "%02x".format(it) })
    }
}
