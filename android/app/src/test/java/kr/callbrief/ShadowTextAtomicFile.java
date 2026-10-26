package kr.callbrief;
import android.util.AtomicFile;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import java.io.*;
import java.nio.file.Files;
/** Test-only controlled commit fault; no production fault hook. */
@Implements(AtomicFile.class)
public class ShadowTextAtomicFile {
    @RealObject private AtomicFile real;
    public static boolean failNext;
    public static byte[] failedStage;
    @Implementation protected void finishWrite(FileOutputStream output)throws IOException{
        if(failNext){failNext=false;failedStage=Files.readAllBytes(new File(real.getBaseFile().getPath()+".new").toPath());throw new IOException("SYNTHETIC_PRIVATE_ERROR_CANARY");}
        Shadow.directlyOn(real,AtomicFile.class,"finishWrite",ClassParameter.from(FileOutputStream.class,output));
    }
}
