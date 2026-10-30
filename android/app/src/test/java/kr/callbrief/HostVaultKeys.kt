package kr.callbrief

import kr.co.callsummary.vault.VaultException
import kr.co.callsummary.vault.VaultKeys
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Public fixture key for host tests only (no AndroidKeyStore). */
class HostVaultKeys : VaultKeys {
    @Volatile var state = VaultKeys.State.MISSING
    @Volatile var wrong = false
    @Volatile var provisionFailure = false
    val provisions = AtomicInteger()
    val accesses = AtomicInteger()
    fun provision(alias: String) {
        check(state == VaultKeys.State.MISSING)
        if(provisionFailure) throw VaultException("KEY_SERVICE_UNAVAILABLE")
        provisions.incrementAndGet(); state = VaultKeys.State.AVAILABLE
    }
    override fun state(alias: String): VaultKeys.State { accesses.incrementAndGet(); return state }
    private fun checkState() {
        accesses.incrementAndGet()
        when (state) {
            VaultKeys.State.MISSING -> throw VaultException("KEY_UNAVAILABLE")
            VaultKeys.State.INVALIDATED -> throw VaultException("KEY_INVALIDATED")
            VaultKeys.State.LOCKED -> throw VaultException("KEY_LOCKED")
            VaultKeys.State.SERVICE_UNAVAILABLE -> throw VaultException("KEY_SERVICE_UNAVAILABLE")
            else -> Unit
        }
    }
    private fun cipher(mode: Int, iv: ByteArray? = null): Cipher {
        checkState()
        val key = SecretKeySpec(ByteArray(32) { if (wrong) 9 else 7 }, "AES")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv))
        }
    }
    override fun encrypt(alias: String) = cipher(Cipher.ENCRYPT_MODE)
    override fun decrypt(alias: String, iv: ByteArray) = cipher(Cipher.DECRYPT_MODE, iv)
}
