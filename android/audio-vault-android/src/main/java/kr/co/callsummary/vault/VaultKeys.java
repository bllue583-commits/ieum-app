package kr.co.callsummary.vault;

import javax.crypto.Cipher;

/** App data-encryption capability, unrelated to native login credentials. Never exports key bytes. */
public interface VaultKeys {
    enum State { AVAILABLE, MISSING, INVALIDATED, LOCKED, SERVICE_UNAVAILABLE }
    State state(String alias);
    Cipher encrypt(String existingAlias);
    Cipher decrypt(String existingAlias,byte[] iv);
}
