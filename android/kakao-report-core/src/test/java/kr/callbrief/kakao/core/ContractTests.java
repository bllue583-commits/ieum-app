package kr.callbrief.kakao.core;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static kr.callbrief.kakao.core.DeliveryResult.Status.*;

/** Synthetic fixtures only. The only sockets this test opens are bound to 127.0.0.1. */
public final class ContractTests {
    static final Instant NOW=Instant.parse("2026-10-12T00:00:00Z");
    static final String USER="synthetic_user_001", LANDING="https://example.org/ieum";
    static final VerifiedSession SESSION=new VerifiedSession("1001",12345,"SYNTHETIC_ACCESS_TOKEN",NOW.plusSeconds(3600),true);
    static final DeliveryCoordinator.Consent CONSENT=new DeliveryCoordinator.Consent(true,USER,"1001",DeliveryCoordinator.POLICY_VERSION);
    static int passed;
    interface Checked { void run() throws Exception; }
    static void test(String name,Checked code) throws Exception { code.run(); passed++; System.out.println("PASS "+name); }
    static void check(boolean value) { if(!value) throw new AssertionError("contract_failed"); }
    static void status(DeliveryResult result,DeliveryResult.Status expected) { if(result.status!=expected) throw new AssertionError("expected="+expected+" actual="+result.status); }
    static void rejects(Checked action) throws Exception { boolean rejected=false; try { action.run(); } catch(IllegalArgumentException e) { rejected=true; } check(rejected); }
    static DailyReport report(boolean finalValue,boolean synthetic,String summary) { return new DailyReport(USER,LocalDate.parse("2026-10-11"),ZoneId.of("Asia/Seoul"),summary,finalValue,synthetic); }
    static DailyReport report() { return report(true,true,"합성 예시: 완료 2건, 오늘 확인할 일 1건.\n예시 안건을 앱에서 확인해 주세요."); }
    static final class MemoryLedger implements DeliveryLedger {
        final Map<String,DeliveryResult> entries=new HashMap<>(); boolean claimFails,completeFails;
        public synchronized Claim claim(String key,String payloadHash,Instant now) {
            if(claimFails) throw new IllegalStateException("synthetic_storage_failure");
            DeliveryResult old=entries.get(key);
            if(old!=null) return new Claim(false,new DeliveryResult(old.status==IN_FLIGHT?UNKNOWN:old.status));
            entries.put(key,new DeliveryResult(IN_FLIGHT)); return new Claim(true,new DeliveryResult(IN_FLIGHT));
        }
        public synchronized void complete(String key,DeliveryResult result) {
            if(completeFails) throw new IllegalStateException("synthetic_commit_failure"); entries.put(key,result);
        }
    }
    static final class Server implements AutoCloseable {
        final HttpServer server; final AtomicInteger calls=new AtomicInteger(),redirectCalls=new AtomicInteger();
        final AtomicReference<String> method=new AtomicReference<>(),auth=new AtomicReference<>(),type=new AtomicReference<>(),body=new AtomicReference<>();
        final ExecutorService executor=Executors.newCachedThreadPool(r->{ Thread t=new Thread(r); t.setDaemon(true); return t; });
        volatile int responseStatus=200, delayMs; volatile String response="{\"result_code\":0}", retryAfter;
        Server() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
            server.createContext(MemoRestTransport.PATH,x->{
                calls.incrementAndGet(); method.set(x.getRequestMethod()); auth.set(x.getRequestHeaders().getFirst("Authorization"));
                type.set(x.getRequestHeaders().getFirst("Content-Type")); body.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                try {
                    if(delayMs>0) Thread.sleep(delayMs);
                    if(retryAfter!=null) x.getResponseHeaders().set("Retry-After",retryAfter);
                    if(responseStatus==302) x.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");
                    byte[] bytes=response.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type","application/json");
                    x.sendResponseHeaders(responseStatus,bytes.length); x.getResponseBody().write(bytes);
                } catch(Exception ignored) { /* expected in timeout test */ } finally { x.close(); }
            });
            server.createContext("/redirected",x->{ redirectCalls.incrementAndGet(); x.sendResponseHeaders(200,0); x.close(); });
            server.start();
        }
        MemoRestTransport transport() { return MemoRestTransport.loopbackContractTest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+MemoRestTransport.PATH),500); }
        DeliveryResult send(MemoryLedger ledger) { return new DeliveryCoordinator(ledger,transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(),SESSION,()->CONSENT,LANDING,NOW); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        System.out.println("SYNTHETIC CONTRACT TESTS — no real Kakao login or message transmission");
        test("official_endpoint_is_fixed",()->check(MemoRestTransport.LIVE_ENDPOINT.toString().equals("https://kapi.kakao.com/v2/api/talk/memo/default/send")));
        test("disabled_mode_performs_no_post",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.DISABLED,report(),SESSION,()->CONSENT,LANDING,NOW),DISABLED); check(s.calls.get()==0); } });
        test("unfinished_report_performs_no_post",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(false,true,"합성"),SESSION,()->CONSENT,LANDING,NOW),NOT_FINALIZED); check(s.calls.get()==0); } });
        test("live_mode_rejects_test_transport",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.LIVE_APPROVED,report(),SESSION,()->CONSENT,LANDING,NOW),DISABLED); check(s.calls.get()==0); } });
        test("test_mode_rejects_personal_report",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(true,false,"가상의 내용"),SESSION,()->CONSENT,LANDING,NOW),DISABLED); check(s.calls.get()==0); } });
        test("local_opt_in_required",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(),SESSION,()->new DeliveryCoordinator.Consent(false,USER,"1001",DeliveryCoordinator.POLICY_VERSION),LANDING,NOW),LOCAL_CONSENT_REQUIRED); check(s.calls.get()==0); } });
        test("changed_consent_policy_stops_post",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(),SESSION,()->new DeliveryCoordinator.Consent(true,USER,"1001","old_policy"),LANDING,NOW),LOCAL_CONSENT_REQUIRED); check(s.calls.get()==0); } });
        test("other_kakao_account_stops_post",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(),SESSION,()->new DeliveryCoordinator.Consent(true,USER,"2002",DeliveryCoordinator.POLICY_VERSION),LANDING,NOW),ACCOUNT_MISMATCH); check(s.calls.get()==0); } });
        test("other_app_user_stops_post",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(),SESSION,()->new DeliveryCoordinator.Consent(true,"synthetic_user_002","1001",DeliveryCoordinator.POLICY_VERSION),LANDING,NOW),ACCOUNT_MISMATCH); check(s.calls.get()==0); } });
        test("scope_not_agreed_stops_post",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(),new VerifiedSession("1001",12345,"SYNTHETIC_ACCESS_TOKEN",NOW.plusSeconds(3600),false),()->CONSENT,LANDING,NOW),SCOPE_REQUIRED); check(s.calls.get()==0); } });
        test("expiring_token_stops_post",()->{ try(Server s=new Server()) { status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(),new VerifiedSession("1001",12345,"SYNTHETIC_ACCESS_TOKEN",NOW.plusSeconds(30),true),()->CONSENT,LANDING,NOW),AUTH_REQUIRED); check(s.calls.get()==0); } });
        test("refresh_policy_uses_sdk_expiry",()->{ check(TokenRenewalPolicy.decide(NOW.plusSeconds(61),NOW.plusSeconds(10000),NOW)==TokenRenewalPolicy.Action.VERIFY); check(TokenRenewalPolicy.decide(NOW.plusSeconds(60),NOW.plusSeconds(10000),NOW)==TokenRenewalPolicy.Action.REFRESH); check(TokenRenewalPolicy.decide(NOW.plusSeconds(100),NOW,NOW)==TokenRenewalPolicy.Action.VERIFY); check(TokenRenewalPolicy.decide(NOW.plusSeconds(30),NOW,NOW)==TokenRenewalPolicy.Action.USER_LOGIN_REQUIRED); check(TokenRenewalPolicy.decide(null,null,NOW)==TokenRenewalPolicy.Action.USER_LOGIN_REQUIRED); });
        test("short_korean_summary_and_local_detail",()->{ MemoTemplate t=new MemoTemplate(report(),LANDING); check(t.text.length()<=200); check(t.text.contains("일일 요약")); check(t.androidExecutionParams.equals("screen=digest&date=2026-10-11")); check(!t.json().contains("SYNTHETIC_ACCESS_TOKEN")); check(!t.json().contains(USER)); });
        test("long_korean_summary_is_shortened",()->{ MemoTemplate t=new MemoTemplate(report(true,true,"가".repeat(800)),LANDING); check(t.text.length()==200); check(t.text.contains("…")); });
        test("emoji_is_not_split",()->{ MemoTemplate t=new MemoTemplate(report(true,true,"😀".repeat(200)),LANDING); check(t.text.length()<=200); KakaoJson.validateUnicode(t.text); });
        test("landing_link_cannot_carry_credentials_or_report_query",()->{ rejects(()->new MemoTemplate(report(),"https://example.org/report?token=synthetic")); rejects(()->new MemoTemplate(report(),"https://user@example.org/report")); rejects(()->new MemoTemplate(report(),"https://example.org/report#synthetic")); rejects(()->new MemoTemplate(report(),"https://placeholder.invalid")); });
        test("json_escapes_summary_strings",()->{ MemoTemplate t=new MemoTemplate(report(true,true,"합성 \"인용\" \\ 줄\n다음 줄"),LANDING); Map<String,Object> json=KakaoJson.object(t.json().getBytes(StandardCharsets.UTF_8)); check(json.get("text").equals(t.text)); });
        test("malformed_json_and_duplicate_keys_rejected",()->{ rejects(()->KakaoJson.object("{\"result_code\":1,\"result_code\":0}".getBytes(StandardCharsets.UTF_8))); rejects(()->KakaoJson.object("{\"result_code\":0} trailing".getBytes(StandardCharsets.UTF_8))); rejects(()->KakaoJson.object(new byte[]{(byte)0xc3,0x28})); });
        test("token_header_injection_rejected",()->rejects(()->new VerifiedSession("1001",12345,"synthetic\r\nInjected",NOW.plusSeconds(3600),true)));
        test("loopback_test_cannot_target_external_endpoint",()->{ rejects(()->MemoRestTransport.loopbackContractTest(MemoRestTransport.LIVE_ENDPOINT,100)); rejects(()->MemoRestTransport.loopbackContractTest(URI.create("http://example.org:80"+MemoRestTransport.PATH),100)); });
        test("real_http_form_contract_to_loopback_only",()->{ try(Server s=new Server()) { status(s.send(new MemoryLedger()),TEST_CONTRACT_PASSED); check(s.calls.get()==1); check(s.method.get().equals("POST")); check(s.auth.get().equals("Bearer SYNTHETIC_ACCESS_TOKEN")); check(s.type.get().equals("application/x-www-form-urlencoded;charset=utf-8")); String form=s.body.get(); check(form.startsWith("template_object=")); check(!form.contains("receiver_uuids")); Map<String,Object> json=KakaoJson.object(URLDecoder.decode(form.substring("template_object=".length()),"UTF-8").getBytes(StandardCharsets.UTF_8)); check(json.get("object_type").equals("text")); } });
        test("test_success_is_never_real_sent",()->{ try(Server s=new Server()) { DeliveryResult r=s.send(new MemoryLedger()); check(r.status!=SENT); check(r.messageKo().contains("실제 카카오 발송 없음")); } });
        test("documented_success_shape_required",()->{ MemoRestTransport t=MemoRestTransport.live(); for(String j:new String[]{"{}","{\"result_code\":\"0\"}","{\"result_code\":0.0}","{\"result_code\":0,\"code\":-401}","{\"result_code\":0,\"failure_info\":[]}"}) status(t.decode(200,j.getBytes(StandardCharsets.UTF_8),null,NOW),UNKNOWN); status(t.decode(200,"{\"result_code\":0}".getBytes(StandardCharsets.UTF_8),null,NOW),SENT); });
        test("auth_scope_config_and_talk_errors",()->{ MemoRestTransport t=MemoRestTransport.live(); long[] codes={-401,-101,-402,-3,-5,-903,-501}; DeliveryResult.Status[] expected={AUTH_REQUIRED,AUTH_REQUIRED,SCOPE_REQUIRED,CONFIG_REQUIRED,CONFIG_REQUIRED,CONFIG_REQUIRED,KAKAO_TALK_UNAVAILABLE}; for(int i=0;i<codes.length;i++) status(t.decode(400,("{\"code\":"+codes[i]+"}").getBytes(StandardCharsets.UTF_8),null,NOW),expected[i]); });
        test("quota_retry_is_not_before_next_korea_day",()->{ MemoRestTransport t=MemoRestTransport.live(); DeliveryResult r=t.decode(400,"{\"code\":-532}".getBytes(StandardCharsets.UTF_8),null,NOW); status(r,RATE_LIMITED); check(r.retryNotBefore.equals(Instant.parse("2026-10-12T15:00:00Z"))); r=t.decode(429,"{\"code\":-10}".getBytes(StandardCharsets.UTF_8),"86400",NOW); check(r.retryNotBefore.equals(NOW.plusSeconds(86400))); });
        test("server_error_timeout_code_and_unexpected_status_are_unknown",()->{ MemoRestTransport t=MemoRestTransport.live(); status(t.decode(500,"{\"code\":-1}".getBytes(StandardCharsets.UTF_8),null,NOW),UNKNOWN); status(t.decode(400,"{\"code\":-603}".getBytes(StandardCharsets.UTF_8),null,NOW),UNKNOWN); status(t.decode(201,"{\"code\":-401}".getBytes(StandardCharsets.UTF_8),null,NOW),UNKNOWN); });
        test("redirect_does_not_leak_token_or_trigger_second_request",()->{ try(Server s=new Server()) { s.responseStatus=302; status(s.send(new MemoryLedger()),UNKNOWN); check(s.calls.get()==1 && s.redirectCalls.get()==0); } });
        test("503_retry_after_zero_never_replays_post",()->{ try(Server s=new Server()) { s.responseStatus=503; s.retryAfter="0"; s.response="{\"code\":-1}"; status(s.send(new MemoryLedger()),UNKNOWN); check(s.calls.get()==1); } });
        test("408_never_replays_post",()->{ try(Server s=new Server()) { s.responseStatus=408; s.response="{\"code\":-1}"; status(s.send(new MemoryLedger()),UNKNOWN); check(s.calls.get()==1); } });
        test("network_timeout_is_unknown_without_retry",()->{ try(Server s=new Server()) { s.delayMs=250; MemoRestTransport t=MemoRestTransport.loopbackContractTest(URI.create("http://127.0.0.1:"+s.server.getAddress().getPort()+MemoRestTransport.PATH),75); status(t.send(SESSION,new MemoTemplate(report(),LANDING),NOW),UNKNOWN); check(s.calls.get()==1); } });
        test("duplicate_trigger_does_not_post_again",()->{ try(Server s=new Server()) { MemoryLedger l=new MemoryLedger(); status(s.send(l),TEST_CONTRACT_PASSED); status(s.send(l),TEST_CONTRACT_PASSED); check(s.calls.get()==1); } });
        test("unknown_result_blocks_all_later_posts",()->{ try(Server s=new Server()) { MemoryLedger l=new MemoryLedger(); s.responseStatus=500; status(s.send(l),UNKNOWN); s.responseStatus=200; status(s.send(l),UNKNOWN); check(s.calls.get()==1); } });
        test("parallel_triggers_only_claim_once",()->{ try(Server s=new Server()) { MemoryLedger l=new MemoryLedger(); ExecutorService pool=Executors.newFixedThreadPool(8); try { List<Future<DeliveryResult>> futures=new ArrayList<>(); for(int i=0;i<32;i++) futures.add(pool.submit(()->s.send(l))); for(Future<DeliveryResult> f:futures) f.get(); check(s.calls.get()==1); } finally { pool.shutdownNow(); } } });
        test("pre_post_storage_failure_blocks_network",()->{ try(Server s=new Server()) { MemoryLedger l=new MemoryLedger(); l.claimFails=true; status(s.send(l),STORAGE_FAILURE); check(s.calls.get()==0); } });
        test("ack_commit_failure_never_reports_sent",()->{ try(Server s=new Server()) { MemoryLedger l=new MemoryLedger(); l.completeFails=true; status(s.send(l),UNKNOWN); l.completeFails=false; status(s.send(l),UNKNOWN); check(s.calls.get()==1); } });
        test("consent_rechecked_after_claim",()->{ try(Server s=new Server()) { AtomicInteger reads=new AtomicInteger(); status(new DeliveryCoordinator(new MemoryLedger(),s.transport()).send(DeliveryCoordinator.Mode.SYNTHETIC_CONTRACT_TEST,report(),SESSION,()->reads.incrementAndGet()==1?CONSENT:new DeliveryCoordinator.Consent(false,USER,"1001",DeliveryCoordinator.POLICY_VERSION),LANDING,NOW),LOCAL_CONSENT_REQUIRED); check(s.calls.get()==0); } });
        System.out.println("PASS TOTAL="+passed+"; live_kakao_requests=0; actual_sends_verified=false; schedule_implemented=false");
    }
}
