package kr.callagent.drive;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drive v3 request builder, exercised with a fake HTTP port. It does not own a network client.
 * Approved native wiring must send ONLY its short-lived AuthorizationClient token, disable cookies
 * and redirects, bound response bodies, and never read a browser/session/token cache.
 */
public final class DriveRestTransport implements DriveTransport {
    private static final String API = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3";
    private static final String FIELDS = "id,name,mimeType,parents,owners(permissionId),permissions(id,type,role),trashed,isAppAuthorized,size,version";

    public interface TokenPort extends AuthorizationPort {
        /** Memory-only token from a future approved AuthorizationClient flow. Never persists/logs. */
        String accessToken();
    }
    public interface HttpPort {
        Response execute(Request request) throws DriveException;
    }
    public static final class Request {
        public final String method, url, contentType;
        public final byte[] body;
        public final int maxResponseBytes;
        private final String bearerToken;
        Request(String method, String url, String contentType, byte[] body, int maxResponseBytes, String bearerToken) {
            this.method = method; this.url = url; this.contentType = contentType; this.body = body.clone();
            this.maxResponseBytes = maxResponseBytes; this.bearerToken = bearerToken;
        }
        /** The approved HTTP adapter uses this header. Request.toString never includes it. */
        public String authorizationHeader() { return "Bearer " + bearerToken; }
        @Override public String toString() { return method + " Drive v3 (credentials and body omitted)"; }
    }
    public static final class Response {
        public final int status;
        public final byte[] body;
        public final long retryAfterMillis;
        public Response(int status, byte[] body, long retryAfterMillis) {
            this.status = status; this.body = body.clone(); this.retryAfterMillis = retryAfterMillis;
        }
    }
    public static final class DisabledHttp implements HttpPort {
        @Override public Response execute(Request request) throws DriveException { throw new DriveException(Failure.FORBIDDEN); }
    }
    private final TokenPort tokens;
    private final HttpPort http;
    public DriveRestTransport(TokenPort tokens, HttpPort http) { this.tokens = tokens; this.http = http; }

    @Override public List<String> generateIds(int count) throws DriveException {
        if (count < 1 || count > 20) throw new DriveException(Failure.PERMANENT);
        Map<String, Object> parsed = json(call("GET", API + "/files/generateIds?count=" + count + "&space=drive&type=files", "", new byte[0], StrictJson.MAX_BYTES));
        Object ids = parsed.get("ids");
        if (!(ids instanceof List)) throw new DriveException(Failure.PERMANENT);
        List<String> values = new ArrayList<>();
        for (Object id : (List<?>) ids) { if (!(id instanceof String)) throw new DriveException(Failure.PERMANENT); values.add((String) id); }
        return Collections.unmodifiableList(values);
    }
    @Override public Metadata create(FileSpec spec, byte[] content) throws DriveException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", id(spec.id)); metadata.put("name", spec.name); metadata.put("mimeType", spec.mimeType);
        if (spec.parentId != null) metadata.put("parents", Collections.singletonList(id(spec.parentId)));
        // No sharing, appProperties, appDataFolder, or arbitrary folder discovery.
        if (spec.role == Role.PRIVATE_FOLDER) {
            if (content.length != 0 || spec.parentId != null || !FOLDER_MIME.equals(spec.mimeType))
                throw new DriveException(Failure.PERMANENT);
            return meta(json(call("POST", API + "/files?fields=" + FIELDS,
                    "application/json; charset=UTF-8", StrictJson.bytes(metadata), StrictJson.MAX_BYTES)));
        }
        if (content.length > DriveRelay.MAX_AUDIO_BYTES) throw new DriveException(Failure.PERMANENT);
        byte[] metadataBytes = StrictJson.bytes(metadata);
        String boundary = "call_agent_" + DriveRelay.sha256(metadataBytes).substring(0, 32);
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        write(multipart, "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n");
        multipart.write(metadataBytes, 0, metadataBytes.length);
        write(multipart, "\r\n--" + boundary + "\r\nContent-Type: " + spec.mimeType + "\r\n\r\n");
        multipart.write(content, 0, content.length);
        write(multipart, "\r\n--" + boundary + "--\r\n");
        return meta(json(call("POST", UPLOAD + "/files?uploadType=multipart&fields=" + FIELDS,
                "multipart/related; boundary=" + boundary, multipart.toByteArray(), StrictJson.MAX_BYTES)));
    }
    @Override public Metadata metadata(String fileId) throws DriveException {
        return meta(json(call("GET", API + "/files/" + id(fileId) + "?fields=" + FIELDS,
                "", new byte[0], StrictJson.MAX_BYTES)));
    }
    @Override public Download download(String fileId, int maxBytes) throws DriveException {
        if (maxBytes < 0 || maxBytes > DriveRelay.MAX_AUDIO_BYTES) throw new DriveException(Failure.PERMANENT);
        return new Download(call("GET", API + "/files/" + id(fileId) + "?alt=media", "", new byte[0], maxBytes));
    }
    private byte[] call(String method, String url, String type, byte[] body, int max) throws DriveException {
        if (tokens.state() != AuthorizationPort.State.ACTIVE) throw new DriveException(Failure.UNAUTHORIZED);
        String token = tokens.accessToken();
        if (token == null || token.isEmpty() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new DriveException(Failure.UNAUTHORIZED);
        Response response = http.execute(new Request(method, url, type, body, max, token));
        if (response.body.length > max) throw new DriveException(Failure.PERMANENT);
        if (response.status >= 200 && response.status < 300) return response.body;
        throw classify(response);
    }
    public static DriveException classify(Response response) {
        int status = response.status;
        Failure failure = Failure.PERMANENT;
        if (status == 401) failure = Failure.UNAUTHORIZED;
        else if (status == 404) failure = Failure.NOT_FOUND;
        else if (status == 409) failure = Failure.CONFLICT;
        else if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) failure = Failure.TRANSIENT;
        else if (status == 403) {
            failure = Failure.FORBIDDEN;
            try {
                Map<String, Object> error = StrictJson.object(json(response.body).get("error"));
                Object errors = error.get("errors");
                if (errors instanceof List) {
                    for (Object item : (List<?>) errors) {
                        Object reason = StrictJson.object(item).get("reason");
                        if ("rateLimitExceeded".equals(reason) || "userRateLimitExceeded".equals(reason))
                            failure = Failure.TRANSIENT;
                    }
                }
            } catch (Exception ignored) { /* malformed errors do not broaden retries */ }
        }
        return new DriveException(failure, Math.max(0, Math.min(60_000L, response.retryAfterMillis)));
    }
    private static Metadata meta(Map<String, Object> m) throws DriveException {
        try {
            String owner = "";
            Object owners = m.get("owners");
            if (owners instanceof List && ((List<?>) owners).size() == 1)
                owner = text(StrictJson.object(((List<?>) owners).get(0)).get("permissionId"));
            String parent = null;
            Object parents = m.get("parents");
            if (parents instanceof List && ((List<?>) parents).size() == 1) parent = text(((List<?>) parents).get(0));
            boolean privateOnly = false;
            Object permissions = m.get("permissions");
            if (permissions instanceof List && ((List<?>) permissions).size() == 1) {
                Map<String, Object> permission = StrictJson.object(((List<?>) permissions).get(0));
                privateOnly = !owner.isEmpty() && owner.equals(permission.get("id"))
                        && "owner".equals(permission.get("role")) && "user".equals(permission.get("type"));
            }
            long size = m.containsKey("size") ? Long.parseLong(text(m.get("size"))) : 0;
            return new Metadata(text(m.get("id")), parent, text(m.get("name")), text(m.get("mimeType")),
                    owner, text(m.get("version")), Boolean.TRUE.equals(m.get("trashed")),
                    Boolean.TRUE.equals(m.get("isAppAuthorized")), privateOnly, size);
        } catch (IllegalArgumentException malformed) { throw new DriveException(Failure.PERMANENT); }
    }
    private static Map<String, Object> json(byte[] bytes) throws DriveException {
        try { return StrictJson.object(StrictJson.parse(bytes)); }
        catch (IllegalArgumentException malformed) { throw new DriveException(Failure.PERMANENT); }
    }
    private static String text(Object value) {
        if (!(value instanceof String)) throw new IllegalArgumentException("expected_text"); return (String) value;
    }
    private static String id(String value) throws DriveException {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,256}")) throw new DriveException(Failure.PERMANENT);
        return value;
    }
    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); out.write(bytes, 0, bytes.length);
    }
}
