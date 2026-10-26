package kr.callbrief.kakao.core;

import java.time.Instant;

/** Ephemeral credentials, from the SDK's verified ID + talk_message consent lookup. Never persist/log this object. */
public final class VerifiedSession {
    public final String subject;
    public final int developerAppId;
    public final Instant expiresAt;
    public final boolean talkMessageAgreed;
    final String accessToken;
    public VerifiedSession(String subject,int appId,String accessToken,Instant expiresAt,boolean talkMessageAgreed) {
        if(subject==null || !subject.matches("[1-9][0-9]{0,19}") || appId<=0 || accessToken==null ||
                !accessToken.matches("[A-Za-z0-9._~-]{8,4096}") || expiresAt==null) throw new IllegalArgumentException("invalid_verified_session");
        this.subject=subject; this.developerAppId=appId; this.accessToken=accessToken;
        this.expiresAt=expiresAt; this.talkMessageAgreed=talkMessageAgreed;
    }
    @Override public String toString() { return "VerifiedSession[credentials withheld]"; }
}
