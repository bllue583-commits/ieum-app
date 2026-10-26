package kr.callbrief

import kr.co.callsummary.core.android.KeystoreTextKeys
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Device-local AEAD protection. Separate from producer signature and model execution evidence. */
internal class AstraAudioSealedFiles(private val scope:String) {
    private val alias="ieum.astra-audio-files."+AstraAudioContract.sha(scope.toByteArray(Charsets.UTF_8))
    private val keys=KeystoreTextKeys()
    fun seal(id:String,plain:ByteArray,firstEmptyStore:Boolean):ByteArray=synchronized(LOCK) {
        val key=keys.existing(alias) ?: if(firstEmptyStore)keys.createForEmptyStore(alias) else error("ASTRA_FILE_KEY_UNAVAILABLE")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
        cipher.updateAAD((scope+"\n"+id).toByteArray(Charsets.UTF_8))
        byteArrayOf(1)+cipher.iv+cipher.doFinal(plain)
    }
    fun open(id:String,encoded:ByteArray):ByteArray {
        check(encoded.size in 30..AstraAudioContract.MAX_RESULT+29 && encoded[0]==1.toByte()) { "ASTRA_FILE_AUTHENTICATION" }
        val key=keys.existing(alias) ?: error("ASTRA_FILE_KEY_UNAVAILABLE")
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,encoded.copyOfRange(1,13)))
            cipher.updateAAD((scope+"\n"+id).toByteArray(Charsets.UTF_8));cipher.doFinal(encoded,13,encoded.size-13)
        } catch(_:Exception) {error("ASTRA_FILE_AUTHENTICATION")}
    }
    companion object {private val LOCK=Any()}
}
