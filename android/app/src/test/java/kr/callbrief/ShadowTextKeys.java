package kr.callbrief;

import kr.co.callsummary.core.android.KeystoreTextKeys;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Test-only JCE provider, no production override or actual Android Keystore assertion. */
@Implements(value=KeystoreTextKeys.class,isInAndroidSdk=false)
public class ShadowTextKeys {
    /** The three legacy worker fixtures encrypt settings with this same public key.
     * Their production binding readers now also authenticate those local settings.
     * This shadow supplies the existing key only; unexpected creation remains a failure. */
    @Implements(className="kr.callbrief.SecureSettings$AndroidKeys",isInAndroidSdk=false)
    public static class ExistingApprovedSettings {
        @Implementation protected SecretKey existing(){
            byte[] bytes=new byte[32];java.util.Arrays.fill(bytes,(byte)0x30);
            return new javax.crypto.spec.SecretKeySpec(bytes,"AES");
        }
        @Implementation protected SecretKey createForFirstEmptyStore(){
            throw new AssertionError("UNEXPECTED_HOST_SETTINGS_KEY_CREATION");
        }
    }
    public static final Map<String,SecretKey> KEYS=new ConcurrentHashMap<>();
    public static final AtomicInteger CREATES=new AtomicInteger();
    public static volatile boolean deny;
    @Implementation protected SecretKey existing(String alias){if(deny)throw new IllegalStateException("SYNTHETIC_KEY_FAILURE_CANARY");return KEYS.get(alias);}
    @Implementation protected SecretKey createForEmptyStore(String alias)throws Exception {
        if(deny)throw new IllegalStateException("SYNTHETIC_KEY_FAILURE_CANARY");
        SecretKey old=KEYS.get(alias);if(old!=null)return old;
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey key=generator.generateKey();KEYS.put(alias,key);CREATES.incrementAndGet();return key;
    }
}
