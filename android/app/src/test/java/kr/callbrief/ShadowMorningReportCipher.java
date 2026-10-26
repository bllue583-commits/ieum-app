package kr.callbrief;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.Arrays;

/** Host-only JCE counterpart of the Android Keystore payload cipher. No production bypass. */
@Implements(value=AndroidReportPayloadCipher.class,isInAndroidSdk=false)
public class ShadowMorningReportCipher {
    private String alias;
    @Implementation protected void __constructor__(String value){alias=value;}
    private SecretKey key(boolean create)throws Exception {
        SecretKey key=ShadowTextKeys.KEYS.get(alias);
        if(key!=null)return key;
        if(!create)throw new IllegalStateException("SYNTHETIC_REPORT_KEY_MISSING");
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);key=generator.generateKey();
        ShadowTextKeys.KEYS.put(alias,key);return key;
    }
    @Implementation protected byte[] seal(String text,String aad,boolean existing)throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(!existing));cipher.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted=cipher.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] result=Arrays.copyOf(cipher.getIV(),12+encrypted.length);System.arraycopy(encrypted,0,result,12,encrypted.length);return result;
    }
    @Implementation protected String open(byte[] bytes,String aad)throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));
        cipher.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));return new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),java.nio.charset.StandardCharsets.UTF_8);
    }
}
