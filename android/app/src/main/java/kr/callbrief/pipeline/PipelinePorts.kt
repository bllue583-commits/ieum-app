package kr.callbrief.pipeline

import java.security.PrivateKey
import java.security.PublicKey

/** Shared seams between the transcription lane (A, producer) and the apply lane (C, consumer).
 * Production implementations live in lane A; tests use in-memory fakes. Frozen with C0-C3. */

/** The device identity. Private keys are Android Keystore keys in production and never leave it. */
interface PipelineDeviceKeys {
    /** kid of the signing public key (docs/contracts: deviceId). */
    val deviceId:String
    val signingPublic:PublicKey
    val encryptionPublic:PublicKey
    fun signingPrivate():PrivateKey
    fun encryptionPrivate():PrivateKey
    /** kid of [encryptionPublic], the recipient kid of envelopes sealed to this device. */
    val encryptionKid:String get()=PipelineContract.kid(encryptionPublic)
}

/** The private inbox repository (docs/contracts: layout). Paths are repository-relative. */
interface PipelineInbox {
    /** File bytes, or null when the path does not exist. */
    fun get(path:String):ByteArray?
    /** Create the file. Same bytes already present = success; different bytes = [PipelineContractException] INBOX_CONFLICT. */
    fun putOnce(path:String,bytes:ByteArray,message:String)
    /** File names (not paths) directly under [directory]; empty when the directory does not exist. */
    fun list(directory:String):List<String>
}

/** Compiled-in public configuration (config/pipeline.json via BuildConfig). */
data class PipelineServerKeys(val signing:PublicKey,val encryption:PublicKey) {
    val encryptionKid get()=PipelineContract.kid(encryption)
    companion object {
        /** Null until tools/setup_pipeline.py has written the public keys; the pipeline then stays idle. */
        fun fromConfig(signingSpki:String,encryptionSpki:String):PipelineServerKeys?=
            if(signingSpki.isBlank()||encryptionSpki.isBlank())null
            else PipelineServerKeys(PipelineContract.publicKey(signingSpki),PipelineContract.publicKey(encryptionSpki))
    }
}

/** Lane C implements (it owns cases, tasks and case summaries); lane A calls it to build C1.business. */
interface PipelineBusinessSource {
    /** The C1 `business` block for this recording: boardRevision, linkedCaseId, cases (validated by PipelineContract). */
    fun business(recordingId:String):Map<String,Any?>
}

/** Lane A implements (it owns the transcription queue and keeps every uploaded C1 locally, protected). */
interface PipelineTranscriptIndex {
    /** The exact C1 document that was uploaded, or null when this device never produced it. */
    fun transcript(transcriptId:String):Map<String,Any?>?
    /** recordingId for a transcript produced on this device, or null. */
    fun recordingFor(transcriptId:String):String?
}

/** SecureSettings keys shared by lanes A and C. Values are written only by explicit user actions
 * (settings screen, lane C) except the status keys, which lane A owns. */
object PipelineSettingsKeys {
    /** Fine-grained GitHub token, Contents read/write on the inbox repository only. Never logged. */
    const val INBOX_TOKEN="pipelineInboxToken"
    /** 0 = no consent to send transcripts to the external AI. Incremented on every grant or withdrawal. */
    const val CONSENT_EPOCH="pipelineConsentEpoch"
    const val CONSENT_GRANTED="pipelineConsentGranted"
    /** Incremented whenever exclusions or other source policy change. */
    const val POLICY_EPOCH="pipelinePolicyEpoch"
    /** Lane A status: NOT_DOWNLOADED | DOWNLOADING | READY | FAILED | UNSUPPORTED. */
    const val MODEL_STATE="pipelineModelState"
    const val LAST_TRANSCRIBED_MS="pipelineLastTranscribedMs"
    const val LAST_UPLOAD_MS="pipelineLastUploadMs"
    /** Lane C status. */
    const val LAST_FETCH_MS="pipelineLastFetchMs"
}
