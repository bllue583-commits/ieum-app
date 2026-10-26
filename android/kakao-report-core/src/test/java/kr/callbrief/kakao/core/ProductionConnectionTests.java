package kr.callbrief.kakao.core;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static kr.callbrief.kakao.core.DeliveryResult.Status.*;
/** In-memory synthetic ports only; no sockets, SDK, tokens or provider calls. */
public final class ProductionConnectionTests {
    static final Instant NOW=Instant.parse("2026-10-14T00:00:00Z");
    static final String USER="synthetic_patch_owner",LINK="https://example.org/ieum";
    static final VerifiedSession SESSION=new VerifiedSession("1001",12345,"SYNTHETIC_PATCH_TOKEN",NOW.plusSeconds(3600),true);
    static int pass;
    static void check(boolean v){if(!v)throw new AssertionError();}
    static void test(String name,Runnable r){r.run();pass++;System.out.println("PASS "+name);}
    static DailyReport report(String s){return new DailyReport(USER,LocalDate.parse("2026-10-13"),ZoneId.of("Asia/Seoul"),s,true,true);}
    static DeliveryCoordinator.Consent consent(long epoch){return new DeliveryCoordinator.Consent(true,USER,"1001",DeliveryCoordinator.POLICY_VERSION,epoch);}
    static final class Ledger implements DeliveryLedger {
        String key,payload;DeliveryResult saved;Runnable afterClaim=()->{};
        public Claim claim(String k,String p,Instant now){
            key=k;payload=p;if(saved!=null)return new Claim(false,new DeliveryResult(saved.status==IN_FLIGHT?UNKNOWN:saved.status));
            saved=new DeliveryResult(IN_FLIGHT);afterClaim.run();return new Claim(true,saved);
        }
        public void complete(String key,DeliveryResult result){saved=result;}
    }
    static final class Transport implements MemoTransport {
        int posts;public Kind kind(){return Kind.LOOPBACK_CONTRACT_TEST;}
        public DeliveryResult send(VerifiedSession session,MemoTemplate template,Instant now){posts++;return new DeliveryResult(TEST_CONTRACT_PASSED);}
    }
    static DeliveryResult send(Ledger l,Transport t,Supplier<DeliveryCoordinator.Consent> c,Supplier<Instant> clock,BooleanSupplier current){
        return new DeliveryCoordinator(l,t).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report("개인정보 없는 합성 보고"),SESSION,c,LINK,clock,current);
    }
    public static void main(String[] ignored){
        test("owner_changes_during_claim_prevent_post",()->{Ledger l=new Ledger();Transport t=new Transport();AtomicBoolean current=new AtomicBoolean(true);l.afterClaim=()->current.set(false);check(send(l,t,()->consent(1),()->NOW,current::get).status==ACCOUNT_MISMATCH);check(t.posts==0);});
        test("consent_disable_reenable_same_subject_is_not_aba",()->{Ledger l=new Ledger();Transport t=new Transport();AtomicLong epoch=new AtomicLong(7);l.afterClaim=()->epoch.set(9);check(send(l,t,()->consent(epoch.get()),()->NOW,()->true).status==LOCAL_CONSENT_REQUIRED);check(t.posts==0);});
        test("token_expiry_uses_dispatch_time_after_claim",()->{Ledger l=new Ledger();Transport t=new Transport();AtomicReference<Instant> clock=new AtomicReference<>(NOW);l.afterClaim=()->clock.set(NOW.plusSeconds(3590));check(send(l,t,()->consent(1),clock::get,()->true).status==AUTH_REQUIRED);check(t.posts==0);});
        test("owner_already_changed_opens_no_outbox_claim",()->{Ledger l=new Ledger();Transport t=new Transport();check(send(l,t,()->consent(1),()->NOW,()->false).status==ACCOUNT_MISMATCH);check(l.key==null && t.posts==0);});
        test("valid_current_fixture_posts_once_and_deduplicates",()->{Ledger l=new Ledger();Transport t=new Transport();check(send(l,t,()->consent(1),()->NOW,()->true).status==TEST_CONTRACT_PASSED);send(l,t,()->consent(1),()->NOW,()->true);check(t.posts==1);});
        test("full_snapshot_hash_covers_text_beyond_excerpt",()->{String prefix="합성".repeat(300);DailyReport x=report(prefix+"A"),y=report(prefix+"B");check(new MemoTemplate(x,LINK).json().equals(new MemoTemplate(y,LINK).json()));check(!x.fingerprint().equals(y.fingerprint()));});
        test("outbox_payload_binds_full_snapshot",()->{Ledger l=new Ledger();Transport t=new Transport();DailyReport x=report("합성 보고");new DeliveryCoordinator(l,t).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,x,SESSION,()->consent(1),LINK,()->NOW,()->true);check(l.payload.equals(DeliveryCoordinator.sha256(x.fingerprint()+"\n"+new MemoTemplate(x,LINK).json())));});
        test("long_unicode_is_one_bounded_template_and_static_link",()->{MemoTemplate m=new MemoTemplate(report("😀한글\n".repeat(400)),LINK);check(m.text.length()<=200);KakaoJson.validateUnicode(m.text);check(m.json().contains("앱에서 자세히 보기"));check(!m.json().contains(USER));check(m.androidExecutionParams.equals("screen=digest&date=2026-10-13"));});
        test("http_date_retry_after_never_retries_earlier",()->{MemoRestTransport t=MemoRestTransport.loopbackContractTest(java.net.URI.create("http://127.0.0.1:1"+MemoRestTransport.PATH),500);Instant due=NOW.plusSeconds(3*86400);String header=due.atZone(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);DeliveryResult result=t.decode(429,"{\"code\":-10}".getBytes(java.nio.charset.StandardCharsets.UTF_8),header,NOW);check(result.status==RATE_LIMITED && result.retryNotBefore.equals(due));});
        test("invalid_retry_after_does_not_become_immediate",()->{MemoRestTransport t=MemoRestTransport.loopbackContractTest(java.net.URI.create("http://127.0.0.1:1"+MemoRestTransport.PATH),500);check(t.decode(429,"{\"code\":-10}".getBytes(java.nio.charset.StandardCharsets.UTF_8),"invalid",NOW).retryNotBefore.isAfter(NOW));});
        test("integer_success_required_float_or_missing_unknown",()->{MemoRestTransport t=MemoRestTransport.loopbackContractTest(java.net.URI.create("http://127.0.0.1:1"+MemoRestTransport.PATH),500);for(String body:List.of("{\"result_code\":0.0}","{}","{\"result_code\":0,\"code\":-401}"))check(t.decode(200,body.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,NOW).status==UNKNOWN);});
        test("fresh_access_does_not_require_unneeded_refresh",()->{check(TokenRenewalPolicy.decide(NOW.plusSeconds(3600),NOW.minusSeconds(1),NOW)==TokenRenewalPolicy.Action.VERIFY);check(TokenRenewalPolicy.decide(NOW,null,NOW)==TokenRenewalPolicy.Action.USER_LOGIN_REQUIRED);});
        System.out.println("PASS TOTAL="+pass+"; external_requests=0; actual_receipt=NOT_RUN");
    }
}
