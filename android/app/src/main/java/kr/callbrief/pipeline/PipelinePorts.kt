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
