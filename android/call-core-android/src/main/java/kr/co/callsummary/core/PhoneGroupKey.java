package kr.co.callsummary.core;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Communication group only. Never assigns or merges a person, company, or task. */
public final class PhoneGroupKey {
    private PhoneGroupKey(){}
    public static String key(String syntheticOrUserProvidedNumber,String explicitlySelectedCountry,byte[] appLocalHmacKey) {
        if(appLocalHmacKey==null || appLocalHmacKey.length<32)throw new CoreException("LOCAL_GROUP_KEY_REQUIRED");
        if(syntheticOrUserProvidedNumber==null || !syntheticOrUserProvidedNumber.matches("[+0-9() .-]{3,40}"))throw new CoreException("PHONE_NORMALIZATION_REVIEW_REQUIRED");
        String normalized=syntheticOrUserProvidedNumber.replaceAll("[() .-]","");
        if(!normalized.startsWith("+")) {
            if(!"KR".equals(explicitlySelectedCountry) || !normalized.matches("0[0-9]{8,10}"))throw new CoreException("PHONE_NORMALIZATION_REVIEW_REQUIRED");
            normalized="+82"+normalized.substring(1);
        }
        if(!normalized.matches("[+][1-9][0-9]{7,14}"))throw new CoreException("PHONE_NORMALIZATION_REVIEW_REQUIRED");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(appLocalHmacKey,"HmacSHA256"));
            return "phone_group_"+JsonCanonical.hex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new CoreException("GROUP_HASH_FAILED",e);}
    }
}
