package kr.callbrief.kakao.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.BufferedSink;
import static kr.callbrief.kakao.core.DeliveryResult.Status.*;

/** Single POST. No redirects, implicit retries, configurable live hosts, or raw error logging. */
public final class MemoRestTransport implements MemoTransport {
    public static final String PATH="/v2/api/talk/memo/default/send";
    public static final URI LIVE_ENDPOINT=URI.create("https://kapi.kakao.com"+PATH);
    private final URI endpoint;
    private final Kind kind;
    private final int timeoutMs;
    private final OkHttpClient client;
    private MemoRestTransport(URI endpoint,Kind kind,int timeoutMs) {
        this.endpoint=endpoint; this.kind=kind; this.timeoutMs=timeoutMs;
        client=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
                .connectTimeout(timeoutMs,TimeUnit.MILLISECONDS).readTimeout(timeoutMs,TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs,TimeUnit.MILLISECONDS).callTimeout(timeoutMs,TimeUnit.MILLISECONDS).build();
    }
    public static MemoRestTransport live() { return new MemoRestTransport(LIVE_ENDPOINT,Kind.LIVE_KAKAO,15_000); }
    /** Tests cannot aim at a remote service, even accidentally. */
    public static MemoRestTransport loopbackContractTest(URI endpoint,int timeoutMs) {
        if(!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) ||
                !PATH.equals(endpoint.getPath()) || endpoint.getQuery()!=null || endpoint.getFragment()!=null ||
                endpoint.getUserInfo()!=null || endpoint.getPort()<1 || timeoutMs<50 || timeoutMs>15_000)
            throw new IllegalArgumentException("loopback_test_only");
        return new MemoRestTransport(endpoint,Kind.LOOPBACK_CONTRACT_TEST,timeoutMs);
    }
    @Override public Kind kind() { return kind; }
    @Override public DeliveryResult send(VerifiedSession session,MemoTemplate template,Instant now) {
        try {
            final byte[] body=("template_object="+URLEncoder.encode(template.json(),"UTF-8")).getBytes(StandardCharsets.UTF_8);
            RequestBody oneShot=new RequestBody() {
                @Override public MediaType contentType() { return MediaType.get("application/x-www-form-urlencoded;charset=utf-8"); }
                @Override public long contentLength() { return body.length; }
                // Also blocks status-driven follow-ups (e.g. 503 Retry-After: 0), not just socket retries.
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(body); }
            };
            Request request=new Request.Builder().url(endpoint.toString()).post(oneShot)
                    .header("Authorization","Bearer "+session.accessToken).header("Accept","application/json").build();
            try(Response response=client.newCall(request).execute()) {
                ResponseBody reply=response.body();
                return decode(response.code(),readBounded(reply==null?null:reply.byteStream()),response.header("Retry-After"),now);
            }
        } catch(IOException | RuntimeException e) {
            // A write/read/connect exception never proves the remote POST was not accepted.
            return new DeliveryResult(UNKNOWN);
        } finally { client.connectionPool().evictAll(); }
    }
    private byte[] readBounded(InputStream input) throws IOException {
        if(input==null) return new byte[0];
        try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[1024]; int n;
            while((n=in.read(b))!=-1) { if(out.size()+n>16_384) throw new IOException("response_too_large"); out.write(b,0,n); }
            return out.toByteArray();
        }
    }
    public DeliveryResult decode(int status,byte[] bytes,String retryAfter,Instant now) {
        Map<String,Object> response;
        try { response=KakaoJson.object(bytes); } catch(RuntimeException e) { return new DeliveryResult(UNKNOWN); }
        Object codeValue=response.get("code"); Long code=codeValue instanceof Long ? (Long)codeValue : null;
        if(status==200 && Long.valueOf(0).equals(response.get("result_code")) && !response.containsKey("code") &&
                !response.containsKey("failure_info")) return new DeliveryResult(kind==Kind.LIVE_KAKAO ? SENT : TEST_CONTRACT_PASSED);
        if(status>=500 || status==408 || Long.valueOf(-603).equals(code) || status==200 || status>=300 && status<400)
            return new DeliveryResult(UNKNOWN);
        // Only a documented structured error is treated as a definitive rejection.
        if(code==null || status<400 || status>=500) return new DeliveryResult(UNKNOWN);
        if(code==-401 || code==-101) return new DeliveryResult(AUTH_REQUIRED);
        if(code==-402) return new DeliveryResult(SCOPE_REQUIRED);
        if(code==-3 || code==-5 || code==-903) return new DeliveryResult(CONFIG_REQUIRED);
        if(code==-501) return new DeliveryResult(KAKAO_TALK_UNAVAILABLE);
        if(status==429 || code==-10 || code==-532 || code==-533 || code==-536) {
            Instant nextDay=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            try { if(retryAfter!=null && retryAfter.matches("[0-9]{1,8}")) {
                Instant retry=now.plusSeconds(Long.parseLong(retryAfter)); if(retry.isAfter(nextDay)) nextDay=retry;
            } else if(retryAfter!=null) {
                Instant retry=java.time.ZonedDateTime.parse(retryAfter,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if(retry.isAfter(nextDay))nextDay=retry;
            }} catch(RuntimeException ignored) { /* No early quota retry. */ }
            return new DeliveryResult(RATE_LIMITED,nextDay);
        }
        if(status>=400 && status<500) return new DeliveryResult(FAILED);
        return new DeliveryResult(UNKNOWN);
    }
}
