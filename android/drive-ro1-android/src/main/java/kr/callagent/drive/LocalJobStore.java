package kr.callagent.drive;

/**
 * Consumer must supply one atomic transaction for current local source + active job + proposal.
 * The Android adapter uses the existing repository DB transaction, never a read then later write.
 * No raw audio or access token is stored by this contract.
 */
public interface LocalJobStore {
    /** Adapter wraps storage I/O/commit failures; transaction must roll back before throwing. */
    final class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public StoreException() { super("local_store_failed"); }
    }
    enum Phase { RESERVED, PLACEHOLDER_READY, AUDIO_READY, SENT, CANDIDATE, CANCELLED, REVOKED, STALE, REJECTED }

    final class Source {
        public final String sourceKey, audioSha, ownerBinding, driveAccount, trustedDriveOwnerId, contextJson;
        public final long sourceVersion, audioBytes, baseRevision, userRevision, policyRevision;
        public final long lockRevision, taskRevision, archiveRevision, leaseEpoch;
        public final boolean participantConfirmed, excluded, archived;
        public Source(String sourceKey, String audioSha, long sourceVersion, long audioBytes, long baseRevision,
                      long userRevision, long lockRevision, long taskRevision, long archiveRevision, long leaseEpoch,
                      String ownerBinding, String driveAccount, String trustedDriveOwnerId, String contextJson,
                      long policyRevision, boolean participantConfirmed, boolean excluded, boolean archived) {
            this.sourceKey = sourceKey; this.audioSha = audioSha; this.sourceVersion = sourceVersion;
            this.audioBytes = audioBytes; this.lockRevision = lockRevision; this.taskRevision = taskRevision;
            this.archiveRevision = archiveRevision; this.leaseEpoch = leaseEpoch; this.archived = archived;
            this.baseRevision = baseRevision; this.userRevision = userRevision;
            this.ownerBinding = ownerBinding; this.policyRevision = policyRevision;
            this.driveAccount = driveAccount; this.trustedDriveOwnerId = trustedDriveOwnerId; this.contextJson = contextJson;
            this.participantConfirmed = participantConfirmed; this.excluded = excluded;
        }
        /** Current source identity/lease is the first fence; manual revisions are checked separately. */
        public boolean sameIdentity(Source other) {
            return other != null && sourceKey.equals(other.sourceKey) && audioSha.equals(other.audioSha)
                    && sourceVersion == other.sourceVersion && audioBytes == other.audioBytes
                    && leaseEpoch == other.leaseEpoch && ownerBinding.equals(other.ownerBinding)
                    && driveAccount.equals(other.driveAccount) && trustedDriveOwnerId.equals(other.trustedDriveOwnerId)
                    && policyRevision == other.policyRevision;
        }
        public boolean manualConflict(Source other) {
            return other == null || baseRevision != other.baseRevision || userRevision != other.userRevision
                    || lockRevision != other.lockRevision || taskRevision != other.taskRevision
                    || !contextJson.equals(other.contextJson);
        }
        public boolean same(Source other) {
            return sameIdentity(other) && !manualConflict(other) && archiveRevision == other.archiveRevision
                    && archived == other.archived;
        }
    }

    final class Job {
        public final String jobId, folderId, audioFileId, requestFileId, resultFileId;
        public final Source source;
        public final long authorizationEpoch;
        public final String requestDigest;
        public final Phase phase;
        public final String acceptedResultDigest;
        public final long resultRevision;
        public final byte[] requestBytes, placeholderBytes;
        public Job(String jobId, String folderId, String audioFileId, String requestFileId,
                   String resultFileId, Source source, long authorizationEpoch, String requestDigest,
                   Phase phase, String acceptedResultDigest, long resultRevision, byte[] requestBytes, byte[] placeholderBytes) {
            this.jobId = jobId; this.folderId = folderId; this.audioFileId = audioFileId;
            this.requestFileId = requestFileId; this.resultFileId = resultFileId;
            this.source = source; this.authorizationEpoch = authorizationEpoch;
            this.requestDigest = requestDigest; this.phase = phase;
            this.acceptedResultDigest = acceptedResultDigest;
            this.resultRevision = resultRevision;
            this.requestBytes = requestBytes.clone(); this.placeholderBytes = placeholderBytes.clone();
        }
        public Job phase(Phase next) {
            return new Job(jobId, folderId, audioFileId, requestFileId, resultFileId, source,
                    authorizationEpoch, requestDigest, next, acceptedResultDigest, resultRevision, requestBytes, placeholderBytes);
        }
        public Job receipt(String resultDigest, long revision, boolean proposal) {
            return new Job(jobId, folderId, audioFileId, requestFileId, resultFileId, source,
                    authorizationEpoch, requestDigest, proposal ? Phase.CANDIDATE : phase, resultDigest, revision, requestBytes, placeholderBytes);
        }
        public boolean terminal() {
            return phase == Phase.CANCELLED || phase == Phase.REVOKED || phase == Phase.STALE
                    || phase == Phase.REJECTED;
        }
    }

    final class Receipt {
        public final String ownerId, jobId, digest;
        public final long revision;
        public final byte[] bytes;
        public final boolean proposal, conflict, hidden, writerVerified, executionVerified, quarantined;
        public Receipt(Job job, WireCodec.Result result, boolean conflict, boolean hidden,
                       boolean writerVerified, boolean executionVerified) {
            this.ownerId = job.source.ownerBinding; this.jobId = job.jobId;
            this.digest = result.resultDigest; this.revision = result.revision; this.bytes = result.bytes.clone();
            this.proposal = "PROPOSAL".equals(result.state); this.conflict = conflict; this.hidden = hidden;
            this.writerVerified = writerVerified; this.executionVerified = executionVerified;
            this.quarantined = result.quarantined || !writerVerified;
        }
        private Receipt(Receipt previous, boolean conflict) {
            this.ownerId = previous.ownerId; this.jobId = previous.jobId; this.digest = previous.digest;
            this.revision = previous.revision; this.bytes = previous.bytes.clone(); this.proposal = previous.proposal;
            this.conflict = conflict; this.hidden = previous.hidden; this.writerVerified = previous.writerVerified;
            this.executionVerified = previous.executionVerified; this.quarantined = previous.quarantined;
        }
        public Receipt withConflict(boolean conflict) { return new Receipt(this, conflict); }
    }

    interface Transaction {
        Source source(String sourceKey);
        Job job(String jobId);
        String activeJobId(String sourceKey);
        void putJob(Job job);
        void setActiveJobId(String sourceKey, String jobId);
        Receipt receipt(String ownerId, String jobId, long resultRevision);
        boolean ownerRevoked(String ownerId);
        /** Must be durable and commit in the same transaction as local job revocation. */
        void revokeOwner(String ownerId);
        /** Immutable per (owner,job,revision). Never updates approved/task/manual state or restores archive. */
        void putReceipt(String sourceKey, Receipt receipt);
        /** Separate quarantine, keyed by digest; never replace the immutable receipt at its revision. */
        void putCollision(String sourceKey, Receipt collision);
    }

    interface Work<T> { T run(Transaction tx); }
    <T> T transaction(Work<T> work);
}
