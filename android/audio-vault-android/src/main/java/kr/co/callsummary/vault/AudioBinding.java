package kr.co.callsummary.vault;

import java.util.Objects;

/** Frozen app source fence. sourceVersion is a plaintext version, never a ciphertext hash. */
public final class AudioBinding {
    public static final long MAX_BYTES=25000000;
    public final String localProfileId,recordingId,plaintextSha256;
    public final long sourceVersion,plaintextBytes;
    public AudioBinding(String profile,String recording,long version,String audioSha256,long bytes){
        id(profile);id(recording);hash(audioSha256);
        if(version<1 || bytes<1 || bytes>MAX_BYTES)throw new VaultException("INVALID_AUDIO_BINDING");
        localProfileId=profile;recordingId=recording;sourceVersion=version;plaintextSha256=audioSha256;plaintextBytes=bytes;
    }
    static void id(String value){if(value==null || !value.matches("[A-Za-z0-9_-]{1,80}"))throw new VaultException("INVALID_OPAQUE_ID");}
    static void hash(String value){if(value==null || !value.matches("[0-9a-f]{64}"))throw new VaultException("INVALID_HASH");}
    static void alias(String value){if(value==null || !value.matches("callsummary[.]audio[.]v2[.][A-Za-z0-9_-]{1,80}"))throw new VaultException("INVALID_KEY_ALIAS");}
    public boolean equals(Object object){if(!(object instanceof AudioBinding))return false;AudioBinding b=(AudioBinding)object;return localProfileId.equals(b.localProfileId)&&recordingId.equals(b.recordingId)&&sourceVersion==b.sourceVersion&&plaintextBytes==b.plaintextBytes&&plaintextSha256.equals(b.plaintextSha256);}
    public int hashCode(){return Objects.hash(localProfileId,recordingId,sourceVersion,plaintextBytes,plaintextSha256);}
}
