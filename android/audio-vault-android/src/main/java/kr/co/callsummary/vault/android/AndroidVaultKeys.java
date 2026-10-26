package kr.co.callsummary.vault.android;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.UserNotAuthenticatedException;
import java.security.KeyStore;
import java.security.Key;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import kr.co.callsummary.vault.VaultKeys;
import kr.co.callsummary.vault.VaultException;

/** AndroidKeyStore data key only. No KeyChain/login/token APIs, key export, fallback, or security-setting changes. */
public final class AndroidVaultKeys implements VaultKeys {
    private static void alias(String a){if(a==null||!a.matches("callsummary[.]audio[.]v2[.][A-Za-z0-9_-]{1,80}"))throw new VaultException("INVALID_KEY_ALIAS");}
    private static KeyStore store()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);return ks;}
    public void createNewKey(String a){
        alias(a);try{
            if(store().containsAlias(a))throw new VaultException("KEY_ALIAS_ALREADY_EXISTS");
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(a,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build());
            generator.generateKey(); // Only explicit first-empty-vault initialization may call this.
        }catch(VaultException e){throw e;}catch(Exception e){throw failure(e);}
    }
    private SecretKey existing(String a)throws Exception{alias(a);Key key=store().getKey(a,null);
        if(key==null)throw new VaultException("KEY_UNAVAILABLE");if(!(key instanceof SecretKey)||!"AES".equals(key.getAlgorithm()))throw new VaultException("KEY_TYPE_MISMATCH");return (SecretKey)key;}
    public State state(String a){try{existing(a);return State.AVAILABLE;}catch(VaultException e){return e.code.equals("KEY_UNAVAILABLE")?State.MISSING:State.SERVICE_UNAVAILABLE;}
        catch(KeyPermanentlyInvalidatedException e){return State.INVALIDATED;}catch(UserNotAuthenticatedException e){return State.LOCKED;}catch(Exception e){return State.SERVICE_UNAVAILABLE;}}
    public Cipher encrypt(String a){try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,existing(a));return cipher;}
        catch(VaultException e){throw e;}catch(Exception e){throw failure(e);}}
    public Cipher decrypt(String a,byte[] iv){try{if(iv==null||iv.length!=12)throw new VaultException("INVALID_GCM_IV");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,existing(a),new GCMParameterSpec(128,iv));return cipher;}
        catch(VaultException e){throw e;}catch(Exception e){throw failure(e);}}
    private static VaultException failure(Exception e){if(e instanceof KeyPermanentlyInvalidatedException)return new VaultException("KEY_INVALIDATED",e);
        if(e instanceof UserNotAuthenticatedException)return new VaultException("KEY_LOCKED",e);return new VaultException("KEY_SERVICE_UNAVAILABLE",e);}
}
