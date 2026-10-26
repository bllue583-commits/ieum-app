package kr.co.callsummary.core.android;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.UriPermission;
import kr.co.callsummary.core.CoreException;
import kr.co.callsummary.core.FileCollector;
import kr.co.callsummary.core.CanonicalAudioStore;
import java.util.Map;

/** Uses an existing user-selected persisted read grant. Never requests or expands permissions. */
public final class SelectedTreeSource {
    private SelectedTreeSource(){}
    public static FileCollector.Observation observation(ContentResolver resolver,Uri selectedTree,Uri document,Map<String,Object> metadata,boolean identityExplicitlyVerified) {
        boolean grant=false;for(UriPermission permission:resolver.getPersistedUriPermissions())
            if(permission.isReadPermission() && permission.getUri().equals(selectedTree))grant=true;
        if(!grant)throw new CoreException("FOLDER_READ_GRANT_MISSING");
        try {
            Uri root=DocumentsContract.buildDocumentUriUsingTree(selectedTree,DocumentsContract.getTreeDocumentId(selectedTree));
            boolean child=DocumentsContract.isChildDocument(resolver,root,document);
            if(!DocumentsContract.getDocumentId(document).equals(metadata.get("document_id")))throw new CoreException("DOCUMENT_ID_MISMATCH");
            return new FileCollector.Observation(metadata,selectedTree.toString(),child,identityExplicitlyVerified);
        }catch(CoreException e){throw e;}catch(Exception e){throw new CoreException("SELECTED_FOLDER_UNVERIFIED",e);}
    }
    public static CanonicalAudioStore.Opener opener(ContentResolver resolver,Uri document) {
        return ()->{java.io.InputStream stream=resolver.openInputStream(document);
            if(stream==null)throw new CoreException("SOURCE_UNAVAILABLE");return stream;};
    }
}
