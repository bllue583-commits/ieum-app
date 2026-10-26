package kr.co.callsummary.vault;

/** Immutable app-local blob manifest. Persist with source CAS; never substitute its hash for audio SHA. */
public final class VaultReference {
    public final AudioBinding source;
    public final String keyAlias,ciphertextSha256;
    public final long storageBytes;
    public VaultReference(AudioBinding source,String alias,String storageHash,long bytes){
        if(source==null)throw new VaultException("INVALID_AUDIO_BINDING");AudioBinding.alias(alias);AudioBinding.hash(storageHash);
        if(bytes<=source.plaintextBytes || bytes>source.plaintextBytes+1052)throw new VaultException("INVALID_STORAGE_LENGTH");
        this.source=source;keyAlias=alias;ciphertextSha256=storageHash;storageBytes=bytes;
    }
    public String storageRef(){return "vault2:"+ciphertextSha256;}
}
