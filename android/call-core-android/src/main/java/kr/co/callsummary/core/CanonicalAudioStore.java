package kr.co.callsummary.core;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;

/** Private plaintext prototype. Encryption/retention/backup policy is a separate gate. */
public final class CanonicalAudioStore {
    public interface Opener { InputStream open() throws Exception; }
    public interface DirectorySync { void sync(Path directory) throws Exception; }
    public static final class Blob {
        public final String sha256,storageRef;
        public final long byteCount;
        private Blob(String hash,long count){sha256=hash;storageRef="sha256:"+hash;byteCount=count;}
    }
    private final Path directory;
    private final DirectorySync directorySync;
    public CanonicalAudioStore(Path appPrivateDirectory,DirectorySync sync) {
        directory=appPrivateDirectory.toAbsolutePath().normalize();directorySync=sync;
        try {
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory))throw new CoreException("UNSAFE_STORAGE_DIRECTORY");
            directorySync.sync(directory);
        } catch(CoreException e){throw e;}catch(Exception e){throw new CoreException("STORAGE_INITIALIZATION_FAILED",e);}
    }
    public synchronized Blob stage(Opener opener,long expectedBytes) {
        if(expectedBytes<1 || expectedBytes>25000000)throw new CoreException("UNSUPPORTED_SOURCE");
        Path staged=directory.resolve(".stage-"+UUID.randomUUID());
        try {
            MessageDigest sha=MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream source=opener.open();FileOutputStream sink=new FileOutputStream(staged.toFile())) {
                byte[] buffer=new byte[32768];int n;
                while((n=source.read(buffer))!=-1){if(n==0)continue;count+=n;
                    if(count>expectedBytes)throw new CoreException("SOURCE_CHANGED_DURING_READ");
                    sha.update(buffer,0,n);sink.write(buffer,0,n);}
                if(count!=expectedBytes)throw new CoreException("SOURCE_CHANGED_DURING_READ");
                sink.flush();sink.getFD().sync();
            }
            String hash=JsonCanonical.hex(sha.digest());Path target=path(hash);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                verify(hash,count);Files.delete(staged);
            } else Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);
            directorySync.sync(directory);
            verify(hash,count);
            return new Blob(hash,count);
        } catch(CoreException e){throw e;}catch(Exception e){throw new CoreException("CANONICAL_COPY_FAILED",e);}
        finally {try{Files.deleteIfExists(staged);}catch(Exception ignored){/* Only this temporary copy. */}}
    }
    public void verify(String hash,long expectedBytes) {
        JsonCanonical.validHash(hash);Path file=path(hash);
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))throw new CoreException("CANONICAL_AUDIO_UNAVAILABLE");
            MessageDigest sha=MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream in=new FileInputStream(file.toFile())){byte[] b=new byte[32768];int n;
                while((n=in.read(b))!=-1){count+=n;if(count>expectedBytes)throw new CoreException("CANONICAL_AUDIO_CORRUPT");sha.update(b,0,n);}}
            if(count!=expectedBytes || !hash.equals(JsonCanonical.hex(sha.digest())))throw new CoreException("CANONICAL_AUDIO_CORRUPT");
        }catch(CoreException e){throw e;}catch(Exception e){throw new CoreException("CANONICAL_AUDIO_UNAVAILABLE",e);}
    }
    public InputStream openVerified(String hash,long expectedBytes) {
        verify(hash,expectedBytes);
        try{return new FileInputStream(path(hash).toFile());}catch(Exception e){throw new CoreException("CANONICAL_AUDIO_UNAVAILABLE",e);}
    }
    /** Reconnect a durable content file after a crash before its DB SEAL command. */
    public Blob recoverVerified(String hash,long expectedBytes) {verify(hash,expectedBytes);return new Blob(hash,expectedBytes);}
    private Path path(String hash){JsonCanonical.validHash(hash);return directory.resolve(hash+".audio");}
}
