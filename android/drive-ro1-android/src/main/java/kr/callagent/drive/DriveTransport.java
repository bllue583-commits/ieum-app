package kr.callagent.drive;

import java.util.List;

/** Drive operations only on app-created IDs in a private My Drive folder. */
public interface DriveTransport {
    String FOLDER_MIME = "application/vnd.google-apps.folder";
    String JSON_MIME = "application/json";
    String WAV_MIME = "audio/wav";

    enum Role { PRIVATE_FOLDER, AUDIO, REQUEST, RESULT }

    final class FileSpec {
        public final String id, parentId, name, mimeType, ownerBinding;
        public final Role role;
        public FileSpec(String id, String parentId, String name, String mimeType,
                        String ownerBinding, Role role) {
            this.id = id; this.parentId = parentId; this.name = name;
            this.mimeType = mimeType; this.ownerBinding = ownerBinding; this.role = role;
        }
    }

    final class Metadata {
        public final String id, parentId, name, mimeType, ownerBinding, version;
        public final boolean trashed, isAppAuthorized, privateToOwner;
        public final long size;
        public Metadata(String id, String parentId, String name, String mimeType,
                        String ownerBinding, String version, boolean trashed,
                        boolean isAppAuthorized, boolean privateToOwner, long size) {
            this.id = id; this.parentId = parentId; this.name = name;
            this.mimeType = mimeType; this.ownerBinding = ownerBinding; this.version = version;
            this.trashed = trashed; this.isAppAuthorized = isAppAuthorized;
            this.privateToOwner = privateToOwner; this.size = size;
        }
    }

    final class Download {
        public final byte[] bytes;
        public Download(byte[] bytes) { this.bytes = bytes.clone(); }
    }

    enum Failure { TRANSIENT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, PERMANENT }

    final class DriveException extends Exception {
        private static final long serialVersionUID = 1L;
        public final Failure failure;
        public final long retryAfterMillis;
        public DriveException(Failure failure) { this(failure, 0); }
        public DriveException(Failure failure, long retryAfterMillis) {
            super(failure.name()); this.failure = failure; this.retryAfterMillis = retryAfterMillis;
        }
    }

    /** Persist generated IDs locally before any create. Duplicate create must use those IDs. */
    List<String> generateIds(int count) throws DriveException;
    Metadata create(FileSpec spec, byte[] content) throws DriveException;
    Metadata metadata(String fileId) throws DriveException;
    Download download(String fileId, int maxBytes) throws DriveException;
    /** Poll only the recorded resultFileId. No list-by-name or discovery of replacement results. */
}
