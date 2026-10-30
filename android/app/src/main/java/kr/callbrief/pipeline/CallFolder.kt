package kr.callbrief.pipeline

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.FolderSelectionChange
import kr.callbrief.SecureSettings

/** Choosing the call-recording folder from the new screens. The picker opens at Samsung's
 * "Recordings/Call" folder so it is usually one tap ("이 폴더 사용"). */
internal object CallFolder {
    private val SAMSUNG_CALLS=Uri.parse("content://com.android.externalstorage.documents/document/primary%3ARecordings%2FCall")
    fun pickIntent()=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI,SAMSUNG_CALLS)

    fun selected(a:Activity)=runCatching { SecureSettings(CallPipelineStartup.storage(a)).snapshot().optString("treeUri") }.getOrDefault("").isNotBlank()

    /** Saves the picked folder and starts watching it. Returns a plain sentence for the user. */
    fun save(a:Activity,result:Intent?):String? {
        val uri=result?.data ?: return null
        val storage=CallPipelineStartup.storage(a);val settings=SecureSettings(storage)
        val r=FolderSelectionChange.replace(uri.toString(),
            current={ settings.snapshot().optString("treeUri") },
            acquire={ a.contentResolver.takePersistableUriPermission(Uri.parse(it),Intent.FLAG_GRANT_READ_URI_PERMISSION) },
            saveUriAndMessage={ s -> settings.update { it.put("treeUri",s).put("automaticDiscoverySelectionRevision",it.optLong("automaticDiscoverySelectionRevision",0L)+1L) } },
            release={ a.contentResolver.releasePersistableUriPermission(Uri.parse(it),Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        return when(r) {
            FolderSelectionChange.Result.SAVED,FolderSelectionChange.Result.SAVED_PREVIOUS_GRANT_RETAINED -> {
                runCatching { AutomaticRecordingDiscovery.ensure(storage) }
                runCatching { CallTranscriptionWork.schedule(storage) }
                "녹음 폴더를 연결했어요. 새 통화를 자동으로 정리해요."
            }
            else -> "이 폴더를 연결하지 못했어요. 다시 골라 주세요."
        }
    }
}
