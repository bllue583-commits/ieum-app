package kr.co.callsummary.core.android;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import kr.co.callsummary.core.CanonicalAudioStore;

/** Plaintext prototype explicitly placed under noBackupFilesDir; encryption is unverified. */
public final class AndroidAudioStore {
    private AndroidAudioStore(){}
    public static CanonicalAudioStore create(Context context) {
        return new CanonicalAudioStore(context.getNoBackupFilesDir().toPath().resolve("call-core/audio"),directory->{
            FileDescriptor fd=Os.open(directory.toString(),OsConstants.O_RDONLY,0);
            try{Os.fsync(fd);}finally{Os.close(fd);}
        });
    }
}
