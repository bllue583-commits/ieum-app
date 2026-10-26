package kr.callbrief.kakao.core;

import java.time.Instant;

public interface MemoTransport {
    enum Kind { LIVE_KAKAO, LOOPBACK_CONTRACT_TEST }
    Kind kind();
    DeliveryResult send(VerifiedSession session,MemoTemplate template,Instant now);
}
