package kr.co.ieum.discovery;
import kr.co.ieum.discovery.DiscoveryTypes.Failure;

/** Completeness of the observed bytes, not proof that a recorder will never append again. */
public final class AudioContainer {
    private AudioContainer(){}
    public static void validate(byte[] b){
        if(b.length>=12&&tag(b,0,"RIFF")&&tag(b,8,"WAVE")){wave(b);return;}
        if(b.length>=16&&tag(b,4,"ftyp")){mp4(b);return;}
        throw new Failure("CONTAINER_REVIEW_REQUIRED");
    }
    private static void wave(byte[] b){
        if(u32le(b,4)+8!=b.length)throw new Failure("INCOMPLETE_CONTAINER");
        int p=12,alignment=0;boolean fmt=false,data=false;
        while(p<b.length){if(p+8>b.length)throw new Failure("INCOMPLETE_CONTAINER");long n=u32le(b,p+4);
            long end=(long)p+8+n+(n&1);if(end>b.length)throw new Failure("INCOMPLETE_CONTAINER");
            if(tag(b,p,"fmt ")){if(n<16)throw new Failure("INCOMPLETE_CONTAINER");alignment=(b[p+20]&255)|((b[p+21]&255)<<8);fmt=true;}
            if(tag(b,p,"data")){if(n==0||alignment<=0||n%alignment!=0)throw new Failure("INCOMPLETE_CONTAINER");data=true;}
            p=(int)end;
        }
        if(!fmt||!data)throw new Failure("INCOMPLETE_CONTAINER");
    }
    private static void mp4(byte[] b){
        int p=0;boolean ftyp=false,moov=false,mdat=false;
        while(p<b.length){if(p+8>b.length)throw new Failure("INCOMPLETE_CONTAINER");long n=u32be(b,p);int header=8;
            if(n==1){if(p+16>b.length)throw new Failure("INCOMPLETE_CONTAINER");long high=u32be(b,p+8);if(high!=0)throw new Failure("INCOMPLETE_CONTAINER");n=u32be(b,p+12);header=16;}
            // An open-ended box does not give us a producer-completion boundary.
            if(n<header||n>(long)b.length-p)throw new Failure("INCOMPLETE_CONTAINER");
            if(tag(b,p+4,"ftyp")&&n>=16)ftyp=true;if(tag(b,p+4,"moov"))moov=audioBoxes(b,p+header,p+(int)n,0);if(tag(b,p+4,"mdat")&&n>header)mdat=true;
            p+=(int)n;
        }
        if(!ftyp||!moov||!mdat)throw new Failure("INCOMPLETE_CONTAINER");
    }
    private static boolean audioBoxes(byte[] b,int start,int end,int depth){
        if(depth>12)throw new Failure("CONTAINER_REVIEW_REQUIRED");boolean audio=false;int p=start;
        while(p<end){if(p+8>end)throw new Failure("INCOMPLETE_CONTAINER");long n=u32be(b,p);int header=8;
            if(n==1){if(p+16>end||u32be(b,p+8)!=0)throw new Failure("INCOMPLETE_CONTAINER");n=u32be(b,p+12);header=16;}
            if(n<header||n>(long)end-p)throw new Failure("INCOMPLETE_CONTAINER");
            if(tag(b,p+4,"trak")||tag(b,p+4,"mdia")||tag(b,p+4,"minf")||tag(b,p+4,"stbl")||tag(b,p+4,"edts")||tag(b,p+4,"dinf"))
                audio|=audioBoxes(b,p+header,p+(int)n,depth+1);
            if(tag(b,p+4,"hdlr")&&n>=header+24&&tag(b,p+header+8,"soun"))audio=true;
            p+=(int)n;
        }return audio;
    }
    private static boolean tag(byte[] b,int p,String s){if(p<0||p+s.length()>b.length)return false;for(int i=0;i<s.length();i++)if(b[p+i]!=(byte)s.charAt(i))return false;return true;}
    private static long u32le(byte[] b,int p){return (b[p]&255L)|((b[p+1]&255L)<<8)|((b[p+2]&255L)<<16)|((b[p+3]&255L)<<24);}
    private static long u32be(byte[] b,int p){return ((b[p]&255L)<<24)|((b[p+1]&255L)<<16)|((b[p+2]&255L)<<8)|(b[p+3]&255L);}
}
