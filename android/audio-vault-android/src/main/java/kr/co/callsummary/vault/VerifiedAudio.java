package kr.co.callsummary.vault;

import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;

/** Scope-limited verified memory; no plaintext temporary file or unverified streaming callback. */
public final class VerifiedAudio implements AutoCloseable {
    private byte[] bytes;
    private final Runnable accessCheck;
    public final AudioBinding source;
    VerifiedAudio(AudioBinding source,byte[] verifiedBytes,Runnable accessCheck){this.source=source;bytes=verifiedBytes;this.accessCheck=accessCheck;}
    public synchronized InputStream openStream(){alive();return new InputStream(){
        private int position;
        public int read()throws IOException{synchronized(VerifiedAudio.this){alive();return position==bytes.length?-1:bytes[position++]&255;}}
        public int read(byte[] destination,int offset,int length)throws IOException{synchronized(VerifiedAudio.this){alive();
            if(offset<0 || length<0 || offset>destination.length-length)throw new IndexOutOfBoundsException();if(length==0)return 0;
            if(position==bytes.length)return -1;int n=Math.min(length,bytes.length-position);System.arraycopy(bytes,position,destination,offset,n);position+=n;return n;}}
    };}
    private void alive(){if(bytes==null)throw new VaultException("VERIFIED_AUDIO_RELEASED");
        try{accessCheck.run();}catch(RuntimeException e){close();throw e;}}
    public synchronized void close(){if(bytes!=null){Arrays.fill(bytes,(byte)0);bytes=null;}}
}
