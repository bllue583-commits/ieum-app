package kr.callbrief.kakao.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import static kr.callbrief.kakao.core.DeliveryResult.Status.*;

public final class DeliveryCoordinator {
    public enum Mode { DISABLED, LIVE_APPROVED, SYNTHETIC_CONTRACT_TEST }
    public static final String POLICY_VERSION="kakao-self-summary-v2";
    public static final class Consent {
        public final boolean enabled;
        public final String appUserKey, subject, policyVersion;
        public final long generation;
        public Consent(boolean enabled,String appUserKey,String subject,String policyVersion) {
            this(enabled,appUserKey,subject,policyVersion,0);
        }
        public Consent(boolean enabled,String appUserKey,String subject,String policyVersion,long generation) {
            if(generation<0)throw new IllegalArgumentException("consent_generation");
            this.enabled=enabled; this.appUserKey=appUserKey; this.subject=subject; this.policyVersion=policyVersion;this.generation=generation;
        }
        @Override public String toString() { return "Consent[identities withheld]"; }
    }
    private final DeliveryLedger ledger;
    private final MemoTransport transport;
    public DeliveryCoordinator(DeliveryLedger ledger,MemoTransport transport) { this.ledger=ledger; this.transport=transport; }
    public DeliveryResult send(Mode mode,DailyReport report,VerifiedSession session,Supplier<Consent> currentConsent,String staticLandingUrl,Instant now) {
        return send(mode,report,session,currentConsent,staticLandingUrl,()->now,()->true);
    }
    /** Production boundary: re-evaluate owner/cancellation, durable consent and
     * actual expiry after claiming, immediately before the single POST. */
    public DeliveryResult send(Mode mode,DailyReport report,VerifiedSession session,Supplier<Consent> currentConsent,
            String staticLandingUrl,Supplier<Instant> clock,BooleanSupplier ownerStillCurrent) {
        if(mode==Mode.DISABLED) return new DeliveryResult(DISABLED);
        if(!report.finalized) return new DeliveryResult(NOT_FINALIZED);
        if(mode==Mode.SYNTHETIC_CONTRACT_TEST && (!report.synthetic || transport.kind()!=MemoTransport.Kind.LOOPBACK_CONTRACT_TEST) ||
                mode==Mode.LIVE_APPROVED && transport.kind()!=MemoTransport.Kind.LIVE_KAKAO) return new DeliveryResult(DISABLED);
        final Consent admitted;
        final Instant now;
        try {
            if(!ownerStillCurrent.getAsBoolean())return new DeliveryResult(ACCOUNT_MISMATCH);
            now=clock.get();admitted=currentConsent.get();
            DeliveryResult gate=check(report,session,admitted,now);if(gate!=null)return gate;
        }catch(RuntimeException failure){return new DeliveryResult(STORAGE_FAILURE);}
        MemoTemplate template;
        try { template=new MemoTemplate(report,staticLandingUrl); } catch(RuntimeException e) { return new DeliveryResult(CONFIG_REQUIRED); }
        // One automatic message per owner/account/calendar day. A new revision
        // never bypasses SENT/UNKNOWN. Full snapshot is bound, not only its excerpt.
        String key=sha256(mode.name()+"\n"+session.developerAppId+"\n"+report.appUserKey+"\n"+session.subject+"\n"+report.zone.getId()+"\n"+report.date);
        DeliveryLedger.Claim claim;
        try { claim=ledger.claim(key,sha256(report.fingerprint()+"\n"+template.json()),now); } catch(RuntimeException e) { return new DeliveryResult(STORAGE_FAILURE); }
        if(!claim.acquired) return claim.result;
        DeliveryResult result;
        try {
            Consent current=currentConsent.get();Instant dispatch=clock.get();
            if(!ownerStillCurrent.getAsBoolean())result=new DeliveryResult(ACCOUNT_MISMATCH);
            else if(!sameConsent(admitted,current))result=new DeliveryResult(LOCAL_CONSENT_REQUIRED);
            else {
                DeliveryResult gate=check(report,session,current,dispatch);
                result=gate!=null ? gate : transport.send(session,template,dispatch);
            }
        } catch(RuntimeException e) { result=new DeliveryResult(UNKNOWN); }
        try { ledger.complete(key,result); } catch(RuntimeException e) { return new DeliveryResult(UNKNOWN); }
        return result;
    }
    private boolean sameConsent(Consent a,Consent b) {
        return a!=null && b!=null && a.enabled==b.enabled && a.generation==b.generation &&
            java.util.Objects.equals(a.appUserKey,b.appUserKey) && java.util.Objects.equals(a.subject,b.subject) && java.util.Objects.equals(a.policyVersion,b.policyVersion);
    }
    private DeliveryResult check(DailyReport r,VerifiedSession s,Consent c,Instant now) {
        if(c==null || !c.enabled || !POLICY_VERSION.equals(c.policyVersion)) return new DeliveryResult(LOCAL_CONSENT_REQUIRED);
        if(s==null || !s.expiresAt.isAfter(now.plusSeconds(30))) return new DeliveryResult(AUTH_REQUIRED);
        if(!r.appUserKey.equals(c.appUserKey) || !s.subject.equals(c.subject)) return new DeliveryResult(ACCOUNT_MISMATCH);
        if(!s.talkMessageAgreed) return new DeliveryResult(SCOPE_REQUIRED);
        return null;
    }
    public static String sha256(String value) {
        try {
            byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(); for(byte x:b) out.append(String.format(java.util.Locale.ROOT,"%02x",x&255)); return out.toString();
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("sha256_unavailable"); }
    }
}
