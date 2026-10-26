package kr.callagent.drive;

/** Transfer envelope is separate from the frozen six-field production model wire. */
public interface WireCodec {
    final class Prepared {
        public final String requestDigest;
        public final byte[] requestBytes, placeholderBytes;
        public Prepared(String requestDigest, byte[] requestBytes, byte[] placeholderBytes) {
            this.requestDigest = requestDigest;
            this.requestBytes = requestBytes.clone(); this.placeholderBytes = placeholderBytes.clone();
        }
    }

    final class Result {
        public final boolean complete;
        public final byte[] bytes;
        public final String resultDigest;
        public final String state;
        public final long revision;
        public final boolean quarantined;
        public Result(boolean complete, byte[] bytes, String resultDigest, String state, long revision, boolean quarantined) {
            this.complete = complete; this.bytes = bytes.clone(); this.resultDigest = resultDigest;
            this.state = state; this.revision = revision;
            this.quarantined = quarantined;
        }
    }

    Prepared prepare(LocalJobStore.Source source, String jobId, String folderId, String audioFileId,
                     String requestFileId, String resultFileId);
    /** Strictly verify schema + every request binding + exact six-field candidate payload. */
    Result validateResult(byte[] bytes, LocalJobStore.Job expected);
}
