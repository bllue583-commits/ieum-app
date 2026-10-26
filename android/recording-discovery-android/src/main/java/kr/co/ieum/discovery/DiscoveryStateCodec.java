package kr.co.ieum.discovery;
import java.io.*;
import java.util.Map;
import kr.co.ieum.discovery.DiscoveryTypes.*;
import kr.co.ieum.discovery.DiscoveryStore.State;
/** Pure bounded queue codec. No filesystem, plaintext persistence or worker wire. */
public final class DiscoveryStateCodec {
    private static final int MAX_ROWS=10000,MAX_HISTORY=10000;
    private DiscoveryStateCodec(){}
    public static byte[] encode(State state){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){encode(state,out);}return bytes.toByteArray();}catch(Failure e){throw e;}catch(Exception e){throw new Failure("QUEUE_CODEC_FAILED",e);}}
    public static State decode(byte[] bytes){try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){State state=decode(in);if(in.available()!=0)throw new Failure("QUEUE_CORRUPT");return state;}catch(Failure e){throw e;}catch(Exception e){throw new Failure("QUEUE_CORRUPT",e);}}
    private static void encode(State s,DataOutputStream out)throws Exception{
        out.writeBoolean(s.selection!=null);if(s.selection!=null)selection(out,s.selection);str(out,s.scanCursor);out.writeLong(s.scanRound);out.writeBoolean(s.grantPaused);
        if(s.rows.size()>MAX_ROWS)throw new Failure("QUEUE_CAPACITY_REACHED");out.writeInt(s.rows.size());
        for(Map.Entry<String,Row> e:s.rows.entrySet()){str(out,e.getKey());Row r=e.getValue();str(out,r.recordingId);meta(out,r.metadata);decision(out,r.decision);
            str(out,r.status.name());out.writeLong(r.headRevision);out.writeLong(r.sourceVersion);out.writeLong(r.seenRound);out.writeLong(r.leaseEpoch);
            str(out,r.stableBoot);out.writeLong(r.stableSince);out.writeInt(r.observations);str(out,r.lastHashBoot);out.writeLong(r.lastHashAt);
            str(out,r.leaseBoot);out.writeLong(r.leaseUntil);str(out,r.error);out.writeBoolean(r.pending!=null);if(r.pending!=null)commit(out,r.pending);
            if(r.versions.size()>MAX_HISTORY)throw new Failure("QUEUE_CAPACITY_REACHED");out.writeInt(r.versions.size());
            for(Version v:r.versions){out.writeLong(v.sourceVersion);str(out,v.audioSha256);out.writeLong(v.bytes);str(out,v.operationId);str(out,v.storageRef);}}
        if(s.audit.size()>MAX_HISTORY)throw new Failure("QUEUE_CAPACITY_REACHED");out.writeInt(s.audit.size());for(String a:s.audit)str(out,a);
        // Default/commit-only states retain their original immutable receipt bytes.
        if(s.scanCursor!=null||!s.scanRoundComplete||s.claimCursor!=null){
            out.writeInt(0x44513136);out.writeBoolean(s.scanRoundComplete);str(out,s.claimCursor);
        }
    }
    private static State decode(DataInputStream in)throws Exception{
        State s=new State();if(in.readBoolean())s.selection=selection(in);s.scanCursor=str(in);s.scanRound=in.readLong();s.grantPaused=in.readBoolean();int n=count(in,MAX_ROWS);
        for(int i=0;i<n;i++){String id=str(in);Row r=new Row();r.recordingId=str(in);r.metadata=meta(in);r.decision=decision(in);r.status=Status.valueOf(str(in));
            r.headRevision=in.readLong();r.sourceVersion=in.readLong();r.seenRound=in.readLong();r.leaseEpoch=in.readLong();r.stableBoot=str(in);r.stableSince=in.readLong();r.observations=in.readInt();
            r.lastHashBoot=str(in);r.lastHashAt=in.readLong();r.leaseBoot=str(in);r.leaseUntil=in.readLong();r.error=str(in);if(in.readBoolean())r.pending=commit(in);
            int v=count(in,MAX_HISTORY);for(int j=0;j<v;j++)r.versions.add(new Version(in.readLong(),str(in),in.readLong(),str(in),str(in)));
            if(id==null||r.metadata==null||!id.equals(r.metadata.documentId)||s.rows.put(id,r)!=null)throw new Failure("QUEUE_CORRUPT");}
        int a=count(in,MAX_HISTORY);for(int i=0;i<a;i++)s.audit.add(str(in));
        // Old codec has no footer. A resumed old partial round is conservatively incomplete.
        if(in.available()==0){s.scanRoundComplete=s.scanCursor==null;return s;}
        if(in.readInt()!=0x44513136)throw new Failure("QUEUE_CORRUPT");
        s.scanRoundComplete=in.readBoolean();s.claimCursor=str(in);return s;
    }
    private static int count(DataInputStream in,int max)throws Exception{int n=in.readInt();if(n<0||n>max)throw new Failure("QUEUE_CORRUPT");return n;}
    private static void str(DataOutputStream out,String s)throws Exception{out.writeBoolean(s!=null);if(s!=null)out.writeUTF(s);}
    private static String str(DataInputStream in)throws Exception{return in.readBoolean()?in.readUTF():null;}
    private static void nullableLong(DataOutputStream out,Long value)throws Exception{out.writeBoolean(value!=null);if(value!=null)out.writeLong(value);}
    private static Long nullableLong(DataInputStream in)throws Exception{return in.readBoolean()?in.readLong():null;}
    private static void selection(DataOutputStream out,Selection s)throws Exception{str(out,s.ownerId);str(out,s.treeUri);out.writeLong(s.revision);}
    private static Selection selection(DataInputStream in)throws Exception{return new Selection(str(in),str(in),in.readLong());}
    private static void meta(DataOutputStream out,Metadata m)throws Exception{str(out,m.documentId);str(out,m.displayName);str(out,m.mimeType);nullableLong(out,m.bytes);nullableLong(out,m.modifiedMs);out.writeBoolean(m.partial);out.writeBoolean(m.virtualDocument);}
    private static Metadata meta(DataInputStream in)throws Exception{return new Metadata(str(in),str(in),str(in),nullableLong(in),nullableLong(in),in.readBoolean(),in.readBoolean());}
    private static void decision(DataOutputStream out,Decision d)throws Exception{str(out,d.permission.name());out.writeLong(d.revision);str(out,d.subjectRef);out.writeBoolean(d.synthetic);}
    private static Decision decision(DataInputStream in)throws Exception{return new Decision(Permission.valueOf(str(in)),in.readLong(),str(in),in.readBoolean());}
    private static void commit(DataOutputStream out,Commit c)throws Exception{str(out,c.operationId);str(out,c.recordingId);selection(out,c.selection);meta(out,c.metadata);decision(out,c.decision);out.writeLong(c.headRevision);out.writeLong(c.sourceVersion);str(out,c.audioSha256);out.writeLong(c.bytes);out.writeLong(c.originLeaseEpoch);}
    private static Commit commit(DataInputStream in)throws Exception{return new Commit(str(in),str(in),selection(in),meta(in),decision(in),in.readLong(),in.readLong(),str(in),in.readLong(),in.readLong());}
}
