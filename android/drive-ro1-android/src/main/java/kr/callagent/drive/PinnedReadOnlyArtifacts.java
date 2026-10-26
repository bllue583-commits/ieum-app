package kr.callagent.drive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static kr.callagent.drive.ReadonlyV1Codec.*;

/**
 * Resolves independently approved Library pins from local bytes, never from a remote envelope.
 * A pin proves content identity only. It does not authenticate dot, OAuth, a device or a model.
 * Missing original bundle bytes yield PENDING and can be resolved later without replacing a receipt.
 */
public final class PinnedReadOnlyArtifacts implements SqliteProfileInbox.TrustedArtifactResolver {
    public static final class Reference {
        public final String libraryFileId,fileId,sha256;public final long version,bytes;
        public Reference(String libraryFileId,long version,String fileId,long bytes,String sha256){
            if(libraryFileId==null||!libraryFileId.matches("libfile_[A-Za-z0-9]+")||fileId==null||!fileId.matches("file_[A-Za-z0-9]+")||version<0||version>StrictJson.MAX_INTEGER||bytes<1||bytes>10485760||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("trusted_artifact_pin");
            this.libraryFileId=libraryFileId;this.version=version;this.fileId=fileId;this.bytes=bytes;this.sha256=sha256;
        }
        void matches(Object wire){Map<String,Object> r=object(wire);WireV0Codec.exact(r,"libraryFileId","version","fileId","bytes","sha256");equal(r.get("libraryFileId"),libraryFileId);equal(r.get("fileId"),fileId);equal(r.get("sha256"),sha256);equal(r.get("version"),version);equal(r.get("bytes"),bytes);}
        boolean contains(byte[] content){return content!=null&&content.length==bytes&&DriveRelay.sha256(content).equals(sha256);}
    }
    public static final class Member {
        public final String path,sha256;public final long bytes;
        public Member(String path,long bytes,String sha256){safeMember(path);if(bytes<1||bytes>262144||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("trusted_member_pin");this.path=path;this.bytes=bytes;this.sha256=sha256;}
        void matches(Map<String,Object> a,String pathKey,String hashKey,String rawKey){equal(a.get(pathKey),path);equal(a.get(hashKey),sha256);byte[] raw=((String)a.get(rawKey)).getBytes(StandardCharsets.UTF_8);if(raw.length!=bytes||!DriveRelay.sha256(raw).equals(sha256))bad("PINNED_MEMBER");}
    }
    public interface LocalBytes {
        /** Return immutable already-consumed bytes for this exact pin, or null if unavailable. */
        byte[] read(Reference independentlyApprovedPin) throws IOException;
    }
    public static final class Resolution {
        public final SqliteProfileInbox.Evidence evidence;
        public final boolean packetBytesVerified,bundleBytesVerified;
        public final boolean executionAuthenticated=false,writerAuthenticated=false;
        public final String reason;
        Resolution(SqliteProfileInbox.Evidence e,boolean p,boolean b,String reason){this.evidence=e;this.packetBytesVerified=p;this.bundleBytesVerified=b;this.reason=reason;}
    }
    private final String approvedRequestSha;private final Reference packet,bundle;private final Member analysis,blind,ledger;private final LocalBytes source;
    public PinnedReadOnlyArtifacts(String approvedRequestSha,Reference packet,Reference bundle,Member analysis,Member blind,Member ledger,LocalBytes source){
        if(approvedRequestSha==null||!approvedRequestSha.matches("[0-9a-f]{64}")||packet==null||bundle==null||analysis==null||blind==null||ledger==null||source==null)throw new IllegalArgumentException("trusted_artifact_configuration");
        this.approvedRequestSha=approvedRequestSha;this.packet=packet;this.bundle=bundle;this.analysis=analysis;this.blind=blind;this.ledger=ledger;this.source=source;
    }
    @Override public SqliteProfileInbox.Evidence resolve(ReadonlyV1Codec.Validated result,byte[] request){return inspect(result,request).evidence;}
    public Resolution inspect(ReadonlyV1Codec.Validated result,byte[] frozenRequest){
        boolean packetOk=false;
        try{
            if(!DriveRelay.sha256(frozenRequest).equals(approvedRequestSha))bad("PINNED_REQUEST");
            Map<String,Object> request=WireV0Codec.validateRequest(frozenRequest),x=result.envelope,stt=object(x.get("stt")),a=object(x.get("readOnlyAnalysis"));
            packet.matches(stt.get("packetRef"));bundle.matches(a.get("artifactRef"));
            analysis.matches(a,"analysisMemberPath","analysisSha","rawAnalysisJson");blind.matches(a,"blindInputMemberPath","blindInputSha","blindInputJson");ledger.matches(a,"segmentLedgerMemberPath","segmentLedgerSha","segmentLedgerJson");
            byte[] packetBytes=source.read(packet);if(packetBytes==null)return new Resolution(SqliteProfileInbox.Evidence.PENDING,false,false,"ORIGINAL_PACKET_UNAVAILABLE");
            if(!packet.contains(packetBytes))bad("PINNED_PACKET_BYTES");
            Map<String,Object> p=StrictJson.readOnlyArtifact(packetBytes);checkPacket(p,result,request,frozenRequest);packetOk=true;
            byte[] zip=source.read(bundle);if(zip==null)return new Resolution(SqliteProfileInbox.Evidence.PENDING,true,false,"ORIGINAL_ANALYSIS_BUNDLE_UNAVAILABLE");
            if(!bundle.contains(zip))bad("PINNED_BUNDLE_BYTES");
            Map<String,byte[]> expected=new LinkedHashMap<>();expected.put(analysis.path,((String)a.get("rawAnalysisJson")).getBytes(StandardCharsets.UTF_8));expected.put(blind.path,((String)a.get("blindInputJson")).getBytes(StandardCharsets.UTF_8));expected.put(ledger.path,((String)a.get("segmentLedgerJson")).getBytes(StandardCharsets.UTF_8));
            try{checkZip(zip,expected);}catch(IOException corruptZip){return new Resolution(SqliteProfileInbox.Evidence.REJECTED,true,false,"PINNED_ZIP_INVALID");}
            return new Resolution(SqliteProfileInbox.Evidence.VERIFIED_CONTENT,true,true,"PINNED_CONTENT_ONLY_NO_EXECUTION_OR_WRITER_AUTH");
        }catch(IllegalArgumentException invalid){return new Resolution(SqliteProfileInbox.Evidence.REJECTED,packetOk,false,invalid.getMessage());}
        catch(IOException unavailable){return new Resolution(SqliteProfileInbox.Evidence.PENDING,packetOk,false,"LOCAL_ARTIFACT_READ_UNAVAILABLE");}
    }
    private static void checkPacket(Map<String,Object> p,ReadonlyV1Codec.Validated result,Map<String,Object> request,byte[] frozenRequest){
        Map<String,Object> s=object(p.get("source")),v=object(s.get("inputValidation")),owner=object(request.get("ownerBinding"));
        for(String key:new String[]{"audioFileId","requestFileId","resultFileId"})equal(s.get(key),request.get(key));
        equal(v.get("ownerBinding"),owner);equal(v.get("sha256"),request.get("audioSha"));equal(integer(v.get("byteLength")),request.get("audioBytes"));equal(v.get("requestSha256"),DriveRelay.sha256(frozenRequest));
        for(String key:new String[]{"requestAudioShaMatches","requestAudioBytesMatches","requestAudioFileIdMatches"})equal(v.get(key),true);
        equal(s.get("sourceShaAfterInference"),request.get("audioSha"));equal(p.get("rawSttText"),result.rawText);
        Map<String,Object> b=object(p.get("blindSummaryInput")),inner=StrictJson.readOnlyInner((String)object(result.envelope.get("readOnlyAnalysis")).get("blindInputJson"));
        equal(b.get("sourceKind"),"actual_audio_stt");equal(b.get("text"),result.body);equal(b.get("segments"),inner.get("segments"));equal(p.get("timestampValidation"),inner.get("timestampValidation"));
        Map<String,Object> e=object(p.get("execution"));equal(integer(e.get("returnCode")),0L);equal(e.get("failed"),false);equal(e.get("audioSha256"),request.get("audioSha"));
        for(String key:new String[]{"startedAtUtc","finishedAtUtc","engine","model","version"})if(!(e.get(key) instanceof String)||((String)e.get(key)).isEmpty())bad("PACKET_EXECUTION_RECEIPT");
        equal(object(p.get("scope")).get("synthetic"),true);if(!list(p.get("failures")).isEmpty())bad("PACKET_FAILURES");
        Map<String,Object> metadata=object(s.get("metadata"));String folder=(String)s.get("folderId");
        for(String[] pair:new String[][]{{"folder","folderId"},{"audio","audioFileId"},{"request","requestFileId"},{"result","resultFileId"}}){
            Map<String,Object> m=object(metadata.get(pair[0]));equal(m.get("id"),s.get(pair[1]));equal(m.get("shared"),false);boolean approvedOwner=false;
            for(Object value:list(m.get("permissions"))){Map<String,Object> permission=object(value);if("owner".equals(permission.get("role"))&&"user".equals(permission.get("type"))&&owner.get("driveAccount").equals(permission.get("emailAddress")))approvedOwner=true;}
            if(!approvedOwner)bad("PACKET_PRIVATE_OWNER");if(!"folder".equals(pair[0])&&!list(m.get("parent_ids")).contains(folder))bad("PACKET_PARENT");
        }
    }
    private static void checkZip(byte[] bytes,Map<String,byte[]> expected)throws IOException{
        long expanded=0;Map<String,byte[]> missing=new LinkedHashMap<>(expected);java.util.Set<String> seen=new java.util.HashSet<>();byte[] buffer=new byte[8192];
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){String path=entry.getName();if(entry.isDirectory())path=path.substring(0,path.length()-1);safeMember(path);if(!seen.add(path))bad("ZIP_DUPLICATE_MEMBER");ByteArrayOutputStream member=expected.containsKey(path)?new ByteArrayOutputStream():null;int n;while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>10485760)bad("ZIP_EXPANDED_SIZE");if(member!=null){member.write(buffer,0,n);if(member.size()>262144)bad("ZIP_MEMBER_SIZE");}}if(member!=null){if(!Arrays.equals(expected.get(path),member.toByteArray()))bad("ZIP_MEMBER_BYTES");missing.remove(path);}zip.closeEntry();}}
        if(!missing.isEmpty())bad("ZIP_MEMBER_MISSING");
    }
    private static void equal(Object a,Object b){if(!java.util.Objects.equals(a,b))bad("PINNED_ARTIFACT_BINDING");}
}
