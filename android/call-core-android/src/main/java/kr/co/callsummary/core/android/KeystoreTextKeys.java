package kr.co.callsummary.core.android;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** This key protects SQL text/metadata independently from the audio and settings keys. */
public class KeystoreTextKeys {
    public SecretKey existing(String alias) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(alias))return null;
        return (SecretKey)store.getKey(alias,null);
    }
    public SecretKey createForEmptyStore(String alias) throws Exception {
        // Caller holds the physical store lock and has proved no prior state or legacy refs.
        SecretKey old=existing(alias);if(old!=null)return old;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
}
