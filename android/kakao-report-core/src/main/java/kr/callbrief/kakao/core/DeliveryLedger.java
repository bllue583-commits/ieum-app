package kr.callbrief.kakao.core;

import java.time.Instant;

/** claim must be atomic/durable BEFORE network IO. Never expire or clear IN_FLIGHT/UNKNOWN/SENT automatically. */
public interface DeliveryLedger {
    final class Claim {
        public final boolean acquired;
        public final DeliveryResult result;
        public Claim(boolean acquired,DeliveryResult result) { this.acquired=acquired; this.result=result; }
    }
    Claim claim(String key,String payloadHash,Instant now);
    void complete(String key,DeliveryResult result);
}
