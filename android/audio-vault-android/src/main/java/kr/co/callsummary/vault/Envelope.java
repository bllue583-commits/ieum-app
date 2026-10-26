package kr.co.callsummary.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Small authenticated binary header. No filename, phone, case, or inferred original time. */
final class Envelope {
    static final byte[] MAGIC={'C','A','L','L','V','L','T','2'};
    final AudioBinding source;final String alias;final byte[] iv,prefix;
    private Envelope(AudioBinding source,String alias,byte[] iv,byte[] prefix){this.source=source;this.alias=alias;this.iv=iv.clone();this.prefix=prefix.clone();}
    static Envelope create(AudioBinding source,String alias,byte[] iv)throws Exception{
        AudioBinding.alias(alias);if(iv==null || iv.length!=12)throw new VaultException("INVALID_GCM_IV");
        ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(header);
        h.writeInt(2);h.writeUTF(alias);h.writeUTF(source.localProfileId);h.writeUTF(source.recordingId);h.writeLong(source.sourceVersion);h.writeLong(source.plaintextBytes);h.writeUTF(source.plaintextSha256);h.write(iv);h.flush();
        byte[] payload=header.toByteArray();if(payload.length>1024)throw new VaultException("INVALID_ENVELOPE");
        ByteArrayOutputStream all=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(all);out.write(MAGIC);out.writeInt(payload.length);out.write(payload);out.flush();
        return new Envelope(source,alias,iv,all.toByteArray());
    }
    static Envelope read(DataInputStream input)throws Exception{
        try{return readChecked(input);}
        catch(IOException e){throw new VaultException("INVALID_ENVELOPE",e);}
        catch(VaultException e){if(e.code.equals("UNSUPPORTED_ENVELOPE_VERSION"))throw e;
            throw new VaultException("INVALID_ENVELOPE",e);}
    }
    private static Envelope readChecked(DataInputStream input)throws Exception{
        byte[] magic=new byte[8];input.readFully(magic);if(!Arrays.equals(magic,MAGIC))throw new VaultException("INVALID_ENVELOPE");
        int length=input.readInt();if(length<1 || length>1024)throw new VaultException("INVALID_ENVELOPE");
        byte[] header=new byte[length];input.readFully(header);DataInputStream h=new DataInputStream(new ByteArrayInputStream(header));
        if(h.readInt()!=2)throw new VaultException("UNSUPPORTED_ENVELOPE_VERSION");
        String alias=h.readUTF(),profile=h.readUTF(),recording=h.readUTF();long version=h.readLong(),bytes=h.readLong();String sha=h.readUTF();byte[] iv=new byte[12];h.readFully(iv);
        if(h.read()!=-1)throw new VaultException("INVALID_ENVELOPE");
        Envelope envelope=create(new AudioBinding(profile,recording,version,sha,bytes),alias,iv);
        if(!Arrays.equals(header,Arrays.copyOfRange(envelope.prefix,12,envelope.prefix.length)))throw new VaultException("NONCANONICAL_ENVELOPE");
        return envelope;
    }
}
