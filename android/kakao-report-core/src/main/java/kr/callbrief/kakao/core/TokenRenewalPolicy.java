package kr.callbrief.kakao.core;

import java.time.Instant;

/** Uses actual SDK expiry timestamps. Expired refresh credentials never open a background login screen. */
public final class TokenRenewalPolicy {
    public enum Action { VERIFY, REFRESH, USER_LOGIN_REQUIRED }
    private TokenRenewalPolicy() {}
    public static Action decide(Instant accessExpiry,Instant refreshExpiry,Instant now) {
        if(accessExpiry==null) return Action.USER_LOGIN_REQUIRED;
        if(accessExpiry.isAfter(now.plusSeconds(60))) return Action.VERIFY;
        return refreshExpiry!=null && refreshExpiry.isAfter(now) ? Action.REFRESH : Action.USER_LOGIN_REQUIRED;
    }
}
