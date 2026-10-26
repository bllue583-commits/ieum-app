package kr.callbrief

import android.content.Context
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.PreparedWhisperScheduledTextInput
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Reads only an already delivered synthetic producer artifact. The transport into this
 * app-private directory must be authorized separately; neither payload nor sidecar sets pins. */
internal object PreparedLocalSttFiles {
    fun load(context:Context,scope:DriveALocalScope,plan:ScheduledDotProofPlan):PreparedWhisperScheduledTextInput? {
        check(plan.inputMode==ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC && UUID.fromString(plan.proofRequestId).toString()==plan.proofRequestId){"LOCAL_STT_PLAN_REQUIRED"}
        check(plan.requestSha.matches(Regex("[0-9a-f]{64}")) && plan.evidenceSha.matches(Regex("[0-9a-f]{64}"))){"LOCAL_STT_PRODUCER_PINS_REQUIRED"}
        val account=AccountStorageContext(context,scope.owner).noBackupFilesDir.canonicalFile
        var directory=account
        for(component in listOf("local-stt-prepared-v1",scope.key,plan.proofRequestId)) {
            directory=File(directory,component)
            check(!Files.isSymbolicLink(directory.toPath())){"LOCAL_STT_PRIVATE_PATH_REQUIRED"}
        }
        val request=File(directory,"scheduled-input.json");val evidence=File(directory,"stt-request-evidence.json")
        if(!request.exists() && !evidence.exists())return null
        fun bounded(file:File):ByteArray {
            check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.canonicalFile==file.absoluteFile && file.length() in 1..65536){"LOCAL_STT_PRIVATE_FILE_REQUIRED"}
            return file.inputStream().use{stream->
                val bytes=ByteArray(65537);var size=0
                while(size<bytes.size) {val n=stream.read(bytes,size,bytes.size-size);if(n<0)break;check(n>0){"LOCAL_STT_FILE_READ_FAILED"};size+=n}
                check(size in 1..65536){"LOCAL_STT_FILE_SIZE"};bytes.copyOf(size)
            }
        }
        val input=bounded(request);val proof=bounded(evidence)
        check(DriveRelay.sha256(input)==plan.requestSha && DriveRelay.sha256(proof)==plan.evidenceSha){"LOCAL_STT_PRODUCER_PIN_MISMATCH"}
        return PreparedWhisperScheduledTextInput(input,proof,plan.evidenceSha).also{it.forJob(plan.proofRequestId)}
    }
}
