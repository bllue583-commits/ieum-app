package kr.callagent.drive;

import java.util.Map;
import static kr.callagent.drive.WireV0Codec.*;

/** Explicit profile dispatch. Unknown kinds/versions are recoverable holds, never legacy decoding. */
public final class ProfileResultDispatcher {
    public static final String V0_KIND="CALL_ANALYSIS_RESULT";
    public static final class UnsupportedProfile extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        UnsupportedProfile(){super("UNSUPPORTED_RESULT_PROFILE");}
    }
    public static final class Result {
        public final String kind,digest,state;public final long schemaVersion,revision;public final byte[] bytes;
        public final ReadonlyV1Codec.Validated readOnly;public final WireCodec.Result legacy;public final AutomationV2Codec.Validated api;
        Result(String kind,long version,byte[] bytes,String digest,String state,long revision,ReadonlyV1Codec.Validated readOnly,WireCodec.Result legacy){
            this(kind,version,bytes,digest,state,revision,readOnly,legacy,null);
        }
        Result(String kind,long version,byte[] bytes,String digest,String state,long revision,ReadonlyV1Codec.Validated readOnly,WireCodec.Result legacy,AutomationV2Codec.Validated api){
            this.kind=kind;this.schemaVersion=version;this.bytes=bytes.clone();this.digest=digest;this.state=state;this.revision=revision;this.readOnly=readOnly;this.legacy=legacy;this.api=api;
        }
        public boolean proposal(){return "PROPOSAL".equals(state);}
    }
    public boolean supports(String kind,long version){return (V0_KIND.equals(kind)&&version==0)||(ReadonlyV1Codec.KIND.equals(kind)&&version==1)||(AutomationV2Codec.KIND.equals(kind)&&version==2);}
    public Result validate(byte[] bytes,LocalJobStore.Job expected){
        Map<String,Object> value=StrictJson.object(StrictJson.parseReadOnlyEnvelope(bytes));Object kind=value.get("kind"),version=value.get("schemaVersion");
        if(!(kind instanceof String)||!(version instanceof Long)||!supports((String)kind,(Long)version))throw new UnsupportedProfile();
        if(V0_KIND.equals(kind)){
            WireCodec.Result r=new WireV0Codec().validateResult(bytes,expected);
            return new Result(V0_KIND,0,bytes,r.resultDigest,r.state,r.revision,null,r);
        }
        if(AutomationV2Codec.KIND.equals(kind)){
            AutomationV2Codec.Validated r=new AutomationV2Codec().validate(bytes,expected.requestBytes);
            return new Result(AutomationV2Codec.KIND,2,bytes,r.digest,"PROPOSAL",r.revision,null,null,r);
        }
        ReadonlyV1Codec.Validated r=new ReadonlyV1Codec().validate(bytes,expected.requestBytes);
        return new Result(ReadonlyV1Codec.KIND,1,bytes,r.digest,"PROPOSAL",r.revision,r,null);
    }
}
