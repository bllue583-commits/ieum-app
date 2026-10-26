package kr.co.callsummary.vault.android;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import kr.co.callsummary.vault.EncryptedAudioVault;

/** Construction never generates keys or starts a transport. Call startup cleanup separately on an IO thread. */
public final class AndroidVaultFactory {
    private AndroidVaultFactory(){}
    public static EncryptedAudioVault open(Context context,AndroidVaultKeys keys){
        return new EncryptedAudioVault(context.getNoBackupFilesDir().toPath().resolve("audio-vault-v2"),keys,directory->{
            FileDescriptor fd=Os.open(directory.toString(),OsConstants.O_RDONLY,0);
            try{Os.fsync(fd);}finally{Os.close(fd);}
        });
    }
}
