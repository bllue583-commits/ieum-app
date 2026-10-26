package kr.co.callsummary.vault;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;

/** Separate v2.1 recovery/read-gate patch; v2 audio envelope unchanged. No app DB/network writes. */
public final class EncryptedAudioVault implements AutoCloseable {
    public interface Opener {InputStream open()throws Exception;}
    public interface DirectorySync {void sync(Path path)throws Exception;}
    public interface VerifiedConsumer {void accept(VerifiedAudio audio)throws Exception;}
    public interface ReleaseGate {boolean stillAuthorized(AudioBinding source);}
    /** Caller holds an owner-scoped no-prior-refs reservation through initialization/recovery. */
    public interface EmptyInitializationReservation {boolean stillEmpty(InitializationIntent intent);}
    public interface KeyProvisioner {void createNewKey(String alias);}
    public interface FaultHook {void reached(String point);}
    public enum Status { ENCRYPTED_REFERENCE_PRESENT,STORAGE_MISSING,CORRUPT_STORAGE,UNSUPPORTED_FORMAT,BINDING_MISMATCH,KEY_UNAVAILABLE,KEY_INVALIDATED,KEY_LOCKED,KEY_SERVICE_UNAVAILABLE }
    public static final class DisconnectedDelivery {
        public String connection(){return "NATIVE_AUTH_NOT_CONNECTED";}
        public void deliver(VaultReference reference){throw new VaultException("TRANSPORT_DISCONNECTED");}
    }
    private static final ConcurrentHashMap<Path,ReentrantLock> LOCKS=new ConcurrentHashMap<>();
    private final Path root;private final VaultKeys keys;private final DirectorySync sync;private final FaultHook fault;
    private final AtomicBoolean closed=new AtomicBoolean();
    public EncryptedAudioVault(Path appPrivateRoot,VaultKeys keys,DirectorySync sync){this(appPrivateRoot,keys,sync,p->{});}
    public EncryptedAudioVault(Path appPrivateRoot,VaultKeys keys,DirectorySync sync,FaultHook fault){
        root=appPrivateRoot.toAbsolutePath().normalize();this.keys=keys;this.sync=sync;this.fault=fault;
        try{boolean exists=Files.exists(root,LinkOption.NOFOLLOW_LINKS);Files.createDirectories(root);
            if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new VaultException("UNSAFE_VAULT_DIRECTORY");
            if(!exists && root.getParent()!=null)sync.sync(root.getParent());sync.sync(root);
        }catch(VaultException e){throw e;}catch(Exception e){throw new VaultException("VAULT_INITIALIZATION_FAILED",e);}
    }
    /** Compatibility entrypoint. Native integration must use a held reservation in beginInitialization. */
    public void initializeNewEmptyStore(String alias,boolean noPriorV2References,KeyProvisioner provisioner,Cancellation token){
        AudioBinding.alias(alias);if(!noPriorV2References)throw new VaultException("PRIOR_REFERENCE_REVIEW_REQUIRED");
        beginInitialization(new InitializationIntent(UUID.randomUUID().toString(),alias),intent->noPriorV2References,provisioner,token);
    }
    /** Explicit new-empty-vault operation. Creates durable PENDING before any key provision. */
    public void beginInitialization(InitializationIntent intent,EmptyInitializationReservation reservation,KeyProvisioner provisioner,Cancellation token){
        if(intent==null)throw new VaultException("INITIALIZATION_INTENT_REQUIRED");
        requireReservation(reservation);
        locked(token,()->{
            if(Files.exists(root.resolve(".key-identity"),LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(root.resolve(".initialization-state"),LinkOption.NOFOLLOW_LINKS))throw new VaultException("VAULT_ALREADY_INITIALIZED");
            requireEmptyInitializationFiles(false);
            requireReservedEmpty(intent,reservation,token);
            if(keys.state(intent.keyAlias)!=VaultKeys.State.MISSING)throw new VaultException("NEW_KEY_ALIAS_NOT_MISSING");
            writeInitialization(new InitializationLedger(intent,InitializationReport.State.PENDING));
            fault.reached("AFTER_INITIALIZATION_INTENT");
            finishPendingInitialization(intent,reservation,provisioner,token);
            return null;
        });
    }
    /** Only same durable PENDING intent, no data files, and a held refs=0 reservation can resume. */
    public void recoverPendingInitialization(InitializationIntent expected,EmptyInitializationReservation reservation,KeyProvisioner provisioner,Cancellation token){
        if(expected==null)throw new VaultException("INITIALIZATION_INTENT_REQUIRED");
        requireReservation(reservation);
        locked(token,()->{
            InitializationLedger record=readInitialization();
            if(record==null)throw new VaultException("LEGACY_INITIALIZATION_REVIEW_REQUIRED");
            if(!record.intent.equals(expected))throw new VaultException("INITIALIZATION_INTENT_MISMATCH");
            if(record.state!=InitializationReport.State.PENDING)throw new VaultException("INITIALIZATION_RECOVERY_DENIED");
            requireEmptyInitializationFiles(true);
            requireReservedEmpty(expected,reservation,token);
            finishPendingInitialization(expected,reservation,provisioner,token);
            return null;
        });
    }
    public InitializationReport initializationStatus(Cancellation token){
        return locked(token,()->{
            try{
                InitializationLedger record=readInitialization();
                if(record!=null){
                    if(record.state!=InitializationReport.State.PENDING)ensureKeyIdentity(record.intent.keyAlias);
                    else if(Files.exists(root.resolve(".key-identity"),LinkOption.NOFOLLOW_LINKS))readPendingIdentity(record.intent.keyAlias);
                    return new InitializationReport(record.state,record.intent,keys.state(record.intent.keyAlias));
                }
                if(Files.exists(root.resolve(".key-identity"),LinkOption.NOFOLLOW_LINKS))return new InitializationReport(InitializationReport.State.LEGACY_REVIEW_REQUIRED,null,null);
                try{requireEmptyInitializationFiles(false);return new InitializationReport(InitializationReport.State.EMPTY,null,null);}
                catch(VaultException e){return new InitializationReport(InitializationReport.State.CORRUPT_REVIEW_REQUIRED,null,null);}
            }catch(VaultException e){return new InitializationReport(InitializationReport.State.CORRUPT_REVIEW_REQUIRED,null,null);}
        });
    }
    private static void requireReservation(EmptyInitializationReservation reservation){if(reservation==null)throw new VaultException("INITIALIZATION_RESERVATION_REQUIRED");}
    private void requireReservedEmpty(InitializationIntent intent,EmptyInitializationReservation reservation,Cancellation token){
        check(token);if(!reservation.stillEmpty(intent))throw new VaultException("INITIALIZATION_RESERVATION_LOST");check(token);
    }
    private void finishPendingInitialization(InitializationIntent intent,EmptyInitializationReservation reservation,KeyProvisioner provisioner,Cancellation token)throws Exception{
        Path identity=root.resolve(".key-identity");
        if(Files.exists(identity,LinkOption.NOFOLLOW_LINKS)){
            String previous=readPendingIdentity(intent.keyAlias);
            if(!previous.equals(intent.keyAlias))writeMetadataAtomically(identity,intent.keyAlias.getBytes(StandardCharsets.US_ASCII));
        }else writeMetadataAtomically(identity,intent.keyAlias.getBytes(StandardCharsets.US_ASCII));
        requireReservedEmpty(intent,reservation,token);
        VaultKeys.State state=keys.state(intent.keyAlias);
        switch(state){
            case MISSING:provisioner.createNewKey(intent.keyAlias);break;
            case AVAILABLE:break; // Lost completion response: adopt existing key, never replace it.
            case INVALIDATED:throw new VaultException("KEY_INVALIDATED");
            case LOCKED:throw new VaultException("KEY_LOCKED");
            default:throw new VaultException("KEY_SERVICE_UNAVAILABLE");
        }
        fault.reached("AFTER_KEY_PROVISION");
        requireReservedEmpty(intent,reservation,token);
        requireEmptyInitializationFiles(true);
        // Key operation must be usable before durable READY; no audio is read or released.
        Cipher capability=keys.encrypt(intent.keyAlias);
        if(capability.getIV()==null || capability.getIV().length!=12)throw new VaultException("INVALID_GCM_IV");
        capability.updateAAD(("first-init-check:"+intent.initializationId).getBytes(StandardCharsets.US_ASCII));
        byte[] checkTag=capability.doFinal();
        try{if(checkTag.length!=16)throw new VaultException("INITIALIZATION_KEY_CHECK_FAILED");}
        finally{Arrays.fill(checkTag,(byte)0);}
        writeInitialization(new InitializationLedger(intent,InitializationReport.State.READY));
        fault.reached("AFTER_INITIALIZATION_READY");
    }
    private void requireEmptyInitializationFiles(boolean pending)throws Exception{
        try(java.util.stream.Stream<Path> paths=Files.list(root)){
            for(Path path:(Iterable<Path>)paths::iterator){
                String name=path.getFileName().toString();
                boolean allowed=name.equals(".vault-lock") || (pending && (name.equals(".initialization-state") || name.equals(".key-identity")));
                if(pending && name.matches("[.]init-stage-[0-9a-f-]{36}"))allowed=true;
                if(!allowed || Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new VaultException("NONEMPTY_VAULT_KEY_PROVISION_DENIED");
            }
        }
    }
    private String readPendingIdentity(String expectedAlias)throws Exception{
        Path path=root.resolve(".key-identity");
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path)>128)throw new VaultException("KEY_IDENTITY_MISMATCH");
        String previous=new String(Files.readAllBytes(path),StandardCharsets.US_ASCII);
        if(!expectedAlias.startsWith(previous))throw new VaultException("KEY_IDENTITY_MISMATCH");
        return previous;
    }
    private InitializationLedger readInitialization()throws Exception{
        Path path=root.resolve(".initialization-state");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path)>InitializationLedger.MAX_BYTES)throw new VaultException("INITIALIZATION_STATE_CORRUPT");
        return InitializationLedger.decode(Files.readAllBytes(path));
    }
    private void writeInitialization(InitializationLedger record)throws Exception{
        writeMetadataAtomically(root.resolve(".initialization-state"),record.encode());
    }
    private void writeMetadataAtomically(Path destination,byte[] bytes)throws Exception{
        Path stage=root.resolve(".init-stage-"+UUID.randomUUID());
        try{
            try(FileOutputStream out=new FileOutputStream(stage.toFile())){out.write(bytes);out.flush();out.getFD().sync();}
            Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            sync.sync(root);
        }finally{Files.deleteIfExists(stage);}
    }
    private void recordDataUse(String alias)throws Exception{
        InitializationLedger record=readInitialization();
        if(record==null)return; // Legacy v2 data can open with its existing authenticated key; recovery stays denied.
        if(!record.intent.keyAlias.equals(alias))throw new VaultException("KEY_IDENTITY_MISMATCH");
        if(record.state==InitializationReport.State.PENDING)throw new VaultException("INITIALIZATION_NOT_READY");
        if(record.state==InitializationReport.State.READY){writeInitialization(new InitializationLedger(record.intent,InitializationReport.State.USED));fault.reached("AFTER_INITIALIZATION_USED");}
    }
    public VaultReference encrypt(AudioBinding binding,String alias,Opener source,Cancellation token){
        AudioBinding.alias(alias);return locked(token,()->{
            ensureKeyIdentity(alias);recordDataUse(alias);Cipher cipher=keys.encrypt(alias);Envelope e=Envelope.create(binding,alias,cipher.getIV());cipher.updateAAD(e.prefix);
            Path stage=root.resolve(".stage-"+UUID.randomUUID());byte[] input=new byte[32768];
            try{
                MessageDigest plain=MessageDigest.getInstance("SHA-256"),storage=MessageDigest.getInstance("SHA-256");long count=0;
                try(InputStream in=source.open();FileOutputStream file=new FileOutputStream(stage.toFile());DigestOutputStream out=new DigestOutputStream(file,storage)){
                    out.write(e.prefix);int n;
                    while((n=in.read(input))!=-1){check(token);if(n==0)continue;count+=n;if(count>binding.plaintextBytes)throw new VaultException("SOURCE_CHANGED_DURING_READ");
                        plain.update(input,0,n);byte[] encrypted=cipher.update(input,0,n);if(encrypted!=null)out.write(encrypted);Arrays.fill(input,(byte)0);}
                    if(count!=binding.plaintextBytes || !hex(plain.digest()).equals(binding.plaintextSha256))throw new VaultException("SOURCE_BINDING_MISMATCH");
                    check(token);out.write(cipher.doFinal());out.flush();file.getFD().sync();fault.reached("AFTER_STAGE_FSYNC");
                }
                String storageHash=hex(storage.digest());long bytes=Files.size(stage);VaultReference reference=new VaultReference(binding,alias,storageHash,bytes);
                check(token);Path destination=file(storageHash);
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){checkFile(reference,token);Files.delete(stage);}else Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE);
                fault.reached("AFTER_PROMOTE");sync.sync(root);checkFile(reference,token);fault.reached("BEFORE_RECEIPT");return reference;
            }finally{Arrays.fill(input,(byte)0);try{Files.deleteIfExists(stage);}catch(Exception cleanupFailure){throw new VaultException("TEMP_CLEANUP_PENDING",cleanupFailure);}}
        });
    }
    /** Explicit local playback/review of the selected history version. Not an outgoing analysis request. */
    public void withVerifiedAudioForLocalReview(VaultReference reference,Cancellation token,VerifiedConsumer consumer){
        withVerifiedAudio(reference,token,source->true,consumer);
    }
    public void withVerifiedAudio(VaultReference reference,Cancellation token,ReleaseGate gate,VerifiedConsumer consumer){
        if(gate==null)throw new VaultException("SOURCE_RELEASE_GATE_REQUIRED");
        locked(token,()->{byte[] clear=decryptVerified(reference,token);try(VerifiedAudio audio=new VerifiedAudio(reference.source,clear,()->checkRelease(reference.source,gate,token))){
                check(token);fault.reached("BEFORE_DELIVERY");checkRelease(reference.source,gate,token);consumer.accept(audio);
            }return null;});
    }
    /** Recover a renamed orphan only after complete AEAD+plaintext binding validation. */
    public VaultReference recoverReference(String ciphertextHash,AudioBinding expectedSource,String expectedAlias,Cancellation token){
        AudioBinding.hash(ciphertextHash);AudioBinding.alias(expectedAlias);return locked(token,()->{
            Envelope e=readEnvelope(file(ciphertextHash));if(!e.source.equals(expectedSource)||!e.alias.equals(expectedAlias))throw new VaultException("BINDING_MISMATCH");
            VaultReference r=new VaultReference(expectedSource,expectedAlias,ciphertextHash,Files.size(file(ciphertextHash)));
            byte[] clear=decryptVerified(r,token);Arrays.fill(clear,(byte)0);return r;
        });
    }
    public Status status(VaultReference reference,Cancellation token){
        try{return locked(token,()->{checkFile(reference,token);Envelope e=readEnvelope(file(reference.ciphertextSha256));match(e,reference);
            switch(keys.state(reference.keyAlias)){
                case AVAILABLE:return Status.ENCRYPTED_REFERENCE_PRESENT;
                case MISSING:return Status.KEY_UNAVAILABLE;
                case INVALIDATED:return Status.KEY_INVALIDATED;
                case LOCKED:return Status.KEY_LOCKED;
                default:return Status.KEY_SERVICE_UNAVAILABLE;
            }});
        }catch(VaultException e){if(e.code.equals("STORAGE_MISSING"))return Status.STORAGE_MISSING;if(e.code.equals("BINDING_MISMATCH"))return Status.BINDING_MISMATCH;
            if(e.code.equals("UNSUPPORTED_ENVELOPE_VERSION"))return Status.UNSUPPORTED_FORMAT;
            if(e.code.equals("CORRUPT_STORAGE")||e.code.equals("INVALID_ENVELOPE"))return Status.CORRUPT_STORAGE;throw e;}
    }
    /** Exclusively locked startup cleanup: encrypted stages only; no final blobs, history, or original files. */
    public int cleanupAbandonedStages(Cancellation token){return locked(token,()->{int deleted=0;
        try(java.util.stream.Stream<Path> paths=Files.list(root)){for(Path p:(Iterable<Path>)paths::iterator){check(token);
            if(p.getFileName().toString().matches("[.]stage-[0-9a-f-]{36}") && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)){Files.delete(p);deleted++;}}}
        if(deleted>0)sync.sync(root);return deleted;});}
    private byte[] decryptVerified(VaultReference r,Cancellation token)throws Exception{
        checkFile(r,token);recordDataUse(r.keyAlias);byte[] clear=new byte[(int)r.source.plaintextBytes],input=new byte[32768];int used=0;boolean verified=false;
        try(DataInputStream in=new DataInputStream(new FileInputStream(file(r.ciphertextSha256).toFile()))){
            Envelope e=Envelope.read(in);match(e,r);Cipher cipher=keys.decrypt(r.keyAlias,e.iv);cipher.updateAAD(e.prefix);int n;long stored=e.prefix.length;
            while((n=in.read(input))!=-1){check(token);if(n==0)continue;stored+=n;if(stored>r.storageBytes)throw new VaultException("CORRUPT_STORAGE");byte[] part=cipher.update(input,0,n);if(part!=null)used=appendAndWipe(clear,used,part);}
            if(stored!=r.storageBytes)throw new VaultException("CORRUPT_STORAGE");
            byte[] finalPart=cipher.doFinal();used=appendAndWipe(clear,used,finalPart);
            if(used!=clear.length || !hex(MessageDigest.getInstance("SHA-256").digest(clear)).equals(r.source.plaintextSha256))throw new VaultException("PLAINTEXT_BINDING_MISMATCH");
            check(token);verified=true;return clear;
        }catch(AEADBadTagException e){throw new VaultException("AUTHENTICATION_FAILED",e);}catch(BadPaddingException e){throw new VaultException("AUTHENTICATION_FAILED",e);}
        finally{Arrays.fill(input,(byte)0);if(!verified)Arrays.fill(clear,(byte)0);}
    }
    private int append(byte[] target,int position,byte[] part){if(position+part.length>target.length)throw new VaultException("INVALID_DECRYPTED_LENGTH");System.arraycopy(part,0,target,position,part.length);return position+part.length;}
    private int appendAndWipe(byte[] target,int position,byte[] part){try{return append(target,position,part);}finally{Arrays.fill(part,(byte)0);}}
    private void checkFile(VaultReference r,Cancellation token)throws Exception{
        Path p=file(r.ciphertextSha256);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new VaultException("STORAGE_MISSING");
        if(Files.isSymbolicLink(p)||Files.size(p)!=r.storageBytes)throw new VaultException("CORRUPT_STORAGE");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];long count=0;
        try(InputStream in=new FileInputStream(p.toFile())){int n;while((n=in.read(buffer))!=-1){check(token);count+=n;if(count>r.storageBytes)throw new VaultException("CORRUPT_STORAGE");hash.update(buffer,0,n);}}
        if(count!=r.storageBytes || !hex(hash.digest()).equals(r.ciphertextSha256))throw new VaultException("CORRUPT_STORAGE");
    }
    private Envelope readEnvelope(Path p)throws Exception{if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new VaultException("STORAGE_MISSING");try(DataInputStream in=new DataInputStream(new FileInputStream(p.toFile()))){return Envelope.read(in);}}
    private static void match(Envelope e,VaultReference r){if(!e.source.equals(r.source)||!e.alias.equals(r.keyAlias)||r.storageBytes!=e.prefix.length+r.source.plaintextBytes+16)throw new VaultException("BINDING_MISMATCH");}
    private void ensureKeyIdentity(String alias)throws Exception{Path p=root.resolve(".key-identity");if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>128||!alias.equals(new String(Files.readAllBytes(p),StandardCharsets.US_ASCII)))throw new VaultException("KEY_IDENTITY_MISMATCH");}
    private Path file(String hash){AudioBinding.hash(hash);return root.resolve(hash+".vault");}
    public static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private void check(Cancellation token){if(closed.get())throw new VaultException("CANCELLED");token.check();}
    private void checkRelease(AudioBinding binding,ReleaseGate gate,Cancellation token){
        check(token);if(!gate.stillAuthorized(binding))throw new VaultException("STALE_RELEASE_BINDING");check(token);
    }
    private interface Work<T>{T run()throws Exception;}
    private <T>T locked(Cancellation token,Work<T> work){
        check(token);ReentrantLock local=LOCKS.computeIfAbsent(root,p->new ReentrantLock());boolean entered=false;
        try{
            long deadline=System.nanoTime()+5000000000L;while(!(entered=local.tryLock())){check(token);if(System.nanoTime()>deadline)throw new VaultException("VAULT_BUSY");Thread.sleep(5);}
            Path lock=root.resolve(".vault-lock");if(Files.isSymbolicLink(lock))throw new VaultException("UNSAFE_VAULT_LOCK");
            try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
                FileLock held=null;while(held==null){check(token);try{held=channel.tryLock();}catch(OverlappingFileLockException ignored){}
                    if(held==null){if(System.nanoTime()>deadline)throw new VaultException("VAULT_BUSY");Thread.sleep(5);}}
                try{check(token);return work.run();}finally{held.release();}
            }
        }catch(VaultException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new VaultException("CANCELLED",e);}catch(Exception e){throw new VaultException("VAULT_OPERATION_FAILED",e);}
        finally{if(entered)local.unlock();}
    }
    public void close(){closed.set(true);}
}
