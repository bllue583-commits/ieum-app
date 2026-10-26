package kr.callagent.drive;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import kr.callagent.drive.DriveTransport.DriveException;
import kr.callagent.drive.DriveTransport.Failure;
import kr.callagent.drive.DriveTransport.FileSpec;
import kr.callagent.drive.DriveTransport.Metadata;
import kr.callagent.drive.DriveTransport.Role;
import kr.callagent.drive.LocalJobStore.Job;
import kr.callagent.drive.LocalJobStore.Phase;
import kr.callagent.drive.LocalJobStore.Source;

/** Android-compatible coordinator. All production dependencies are injected; default auth is disabled. */
public final class DriveRelay {
    public static final int MAX_AUDIO_BYTES = 5 * 1024 * 1024;
    public enum Outcome {
        NOT_CONFIGURED, AUTHORIZATION_REQUIRED, SENT, PENDING, CANDIDATE_STORED, DUPLICATE,
        STALE, OLD_RESULT, RESULT_REVISION_CONFLICT, CONFLICT_CANDIDATE_STORED, HIDDEN_CANDIDATE_STORED,
        REMOTE_ERROR, REMOTE_CANCELLED, CANCELLED, REVOKED, POLICY_BLOCKED, HASH_MISMATCH, INVALID_RESULT,
        FILE_BINDING_MISMATCH, WRITER_UNVERIFIED, QUARANTINED, RETRY_LATER, TRANSPORT_BLOCKED, UNKNOWN_JOB,
        LOCAL_STORE_FAILED
    }
    public static final class Reply {
        public final Outcome outcome; public final String jobId;
        Reply(Outcome outcome, String jobId) { this.outcome = outcome; this.jobId = jobId; }
    }
    public interface AudioSource {
        /** Consumer vault must honor check before open and cooperatively during each read. */
        byte[] read(String sourceKey, int maxBytes, RetryPolicy.Check check);
    }
    public interface WriterTrust {
        /** Binding equality and an owner field supplied by the JSON are not writer authentication. */
        boolean verified(Job job, Metadata metadata, byte[] resultBytes);
    }
    public interface BeforeCas { void run(); }
    /** Durable local request->frozen job association must succeed before any file publication. */
    public interface ReservationObserver { void reserved(Job job); }
    private static final class Halt extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Outcome outcome; Halt(Outcome outcome) { super(outcome.name()); this.outcome = outcome; }
    }
    private final AuthorizationPort authorization;
    private final DriveTransport transport;
    private final LocalJobStore store;
    private final WireCodec codec;
    private final RetryPolicy retry;
    private final WriterTrust writerTrust;
    private final BeforeCas beforeCas;

    public DriveRelay(AuthorizationPort authorization, DriveTransport transport, LocalJobStore store,
                      WireCodec codec, RetryPolicy retry, WriterTrust writerTrust) {
        this(authorization, transport, store, codec, retry, writerTrust, () -> {});
    }
    /** Test hook models a local user edit/cancel/revocation just before the atomic CAS. */
    public DriveRelay(AuthorizationPort authorization, DriveTransport transport, LocalJobStore store,
                      WireCodec codec, RetryPolicy retry, WriterTrust writerTrust, BeforeCas beforeCas) {
        this.authorization = authorization; this.transport = transport; this.store = store;
        this.codec = codec; this.retry = retry; this.writerTrust = writerTrust; this.beforeCas = beforeCas;
    }

    public Reply submit(String sourceKey, String requestedJobId, AudioSource audio) {
        return submit(sourceKey, requestedJobId, audio, job -> {});
    }
    public Reply submit(String sourceKey, String requestedJobId, AudioSource audio, ReservationObserver observer) {
        return submit(sourceKey, requestedJobId, audio, observer, null);
    }
    /** Explicit resume only. A changed/absent job cannot fall through to new ID generation. */
    public Reply resume(String exactJobId, AudioSource audio, ReservationObserver observer) {
        Job job = load(exactJobId);
        if (job == null) return reply(Outcome.UNKNOWN_JOB, exactJobId);
        return submit(job.source.sourceKey, exactJobId, audio, observer, exactJobId);
    }
    private Reply submit(String sourceKey, String requestedJobId, AudioSource audio,
                         ReservationObserver observer, String requiredExistingId) {
        String jobId = requestedJobId;
        byte[] audioBytes = null;
        try {
            if (requestedJobId == null || !requestedJobId.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("invalid_job_id");
            Source source = store.transaction(tx -> tx.source(sourceKey));
            checkSource(source);
            Job existing = store.transaction(tx -> {
                String id = tx.activeJobId(sourceKey); return id == null ? null : tx.job(id);
            });
            if (requiredExistingId != null && (existing == null || !requiredExistingId.equals(existing.jobId)))
                throw new Halt(Outcome.STALE);
            if (existing != null && existing.source.same(source) && !existing.terminal()
                    && existing.authorizationEpoch == authorization.epoch()) {
                jobId = existing.jobId;
                if (existing.phase == Phase.SENT || existing.phase == Phase.CANDIDATE) {
                    observer.reserved(existing);
                    return reply(Outcome.DUPLICATE, jobId);
                }
            } else existing = null;
            if (requiredExistingId != null && existing == null) throw new Halt(Outcome.STALE);
            final Job job;
            if (existing != null) job = existing;
            else {
                long epoch = authorization.epoch();
                List<String> ids = retry.execute(() -> checkSource(source), () -> transport.generateIds(4));
                if (ids.size() != 4 || ids.stream().distinct().count() != 4) throw new Halt(Outcome.FILE_BINDING_MISMATCH);
                for (String id : ids) requireId(id);
                WireCodec.Prepared prepared = codec.prepare(source, requestedJobId, ids.get(0), ids.get(1), ids.get(2), ids.get(3));
                Job reserved = new Job(requestedJobId, ids.get(0), ids.get(1), ids.get(2), ids.get(3),
                        source, epoch, prepared.requestDigest, Phase.RESERVED, "", -1,
                        prepared.requestBytes, prepared.placeholderBytes);
                job = store.transaction(tx -> {
                    checkSourceInTransaction(tx, tx.source(sourceKey));
                    if (!source.same(tx.source(sourceKey)) || epoch != authorization.epoch()) throw new Halt(Outcome.STALE);
                    Job collision = tx.job(requestedJobId);
                    if (collision != null) throw new Halt(Outcome.STALE);
                    String activeId = tx.activeJobId(sourceKey);
                    Job active = activeId == null ? null : tx.job(activeId);
                    if (active != null && !active.terminal() && active.source.same(source)
                            && active.authorizationEpoch == epoch) return active;
                    if (active != null && !active.terminal()) tx.putJob(active.phase(Phase.STALE));
                    tx.putJob(reserved); tx.setActiveJobId(sourceKey, reserved.jobId); return reserved;
                });
            }
            jobId = job.jobId;
            observer.reserved(job);
            check(job);
            FileSpec folder = spec(job, Role.PRIVATE_FOLDER);
            ensureCreate(job, folder, new byte[0]);
            Job current = load(job.jobId);
            if (current.phase == Phase.RESERVED) {
                ensureCreate(job, spec(job, Role.RESULT), job.placeholderBytes);
                advance(job, Phase.PLACEHOLDER_READY);
            }
            current = load(job.jobId);
            if (current.phase == Phase.PLACEHOLDER_READY) {
                check(job); // participant confirmed + exclusion policy before every raw read
                audioBytes = audio.read(sourceKey, MAX_AUDIO_BYTES, () -> check(job));
                check(job);
                if (audioBytes == null || audioBytes.length > MAX_AUDIO_BYTES || audioBytes.length < 44
                        || audioBytes.length != job.source.audioBytes || !wav(audioBytes)
                        || !sha256(audioBytes).equals(job.source.audioSha))
                    throw new Halt(Outcome.HASH_MISMATCH);
                ensureCreate(job, spec(job, Role.AUDIO), audioBytes);
                advance(job, Phase.AUDIO_READY);
                Arrays.fill(audioBytes, (byte) 0); audioBytes = null;
            }
            current = load(job.jobId);
            if (current.phase == Phase.AUDIO_READY) {
                // Publishing request is the commit marker. dot must ignore uncommitted folders.
                ensureCreate(job, spec(job, Role.REQUEST), job.requestBytes);
                advance(job, Phase.SENT);
            }
            check(job);
            return reply(Outcome.SENT, jobId);
        } catch (Halt halt) { return reply(halt.outcome, jobId); }
        catch (DriveException e) { return reply(handle(e), jobId); }
        catch (IllegalArgumentException malformed) { return reply(Outcome.INVALID_RESULT, jobId); }
        catch (LocalJobStore.StoreException failed) { return reply(Outcome.LOCAL_STORE_FAILED, jobId); }
        finally { if (audioBytes != null) Arrays.fill(audioBytes, (byte) 0); }
    }

    public Reply poll(String jobId) {
        try {
            Job job = load(jobId);
            if (job == null) return reply(Outcome.UNKNOWN_JOB, jobId);
            checkRetrieval(job);
            if (job.phase != Phase.SENT && job.phase != Phase.CANDIDATE) return reply(Outcome.PENDING, jobId);
            Metadata folder = retry.execute(() -> checkRetrieval(job), () -> transport.metadata(job.folderId));
            validateMeta(folder, spec(job, Role.PRIVATE_FOLDER));
            Metadata before = retry.execute(() -> checkRetrieval(job), () -> transport.metadata(job.resultFileId));
            validateMeta(before, spec(job, Role.RESULT));
            if (before.size > StrictJson.MAX_BYTES) throw new Halt(Outcome.INVALID_RESULT);
            byte[] resultBytes = retry.execute(() -> checkRetrieval(job),
                    () -> transport.download(job.resultFileId, StrictJson.MAX_BYTES)).bytes;
            Metadata after = retry.execute(() -> checkRetrieval(job), () -> transport.metadata(job.resultFileId));
            validateMeta(after, spec(job, Role.RESULT));
            // Prevent trusting bytes read across concurrent replacement of the same file.
            if (!before.version.equals(after.version)) return reply(Outcome.RETRY_LATER, jobId);
            if (after.size != resultBytes.length) throw new Halt(Outcome.INVALID_RESULT);
            WireCodec.Result result = codec.validateResult(resultBytes, job);
            boolean writerVerified = writerTrust.verified(job, after, resultBytes);
            beforeCas.run();
            Outcome outcome = store.transaction(tx -> {
                Job current = tx.job(jobId);
                checkInTransaction(tx, current, true);
                LocalJobStore.Receipt previous = tx.receipt(current.source.ownerBinding, current.jobId, result.revision);
                if (previous != null) {
                    if (previous.digest.equals(result.resultDigest)) return Outcome.DUPLICATE;
                    tx.putCollision(current.source.sourceKey, new LocalJobStore.Receipt(current, result,
                            true, true, false, false));
                    return Outcome.RESULT_REVISION_CONFLICT;
                }
                if (result.revision < current.resultRevision) return Outcome.OLD_RESULT;
                if (current.phase != Phase.SENT && current.phase != Phase.CANDIDATE) throw new Halt(Outcome.STALE);
                Source now = tx.source(current.source.sourceKey);
                boolean conflict = current.source.manualConflict(now);
                boolean hidden = now.archived || current.source.archiveRevision != now.archiveRevision;
                tx.putReceipt(current.source.sourceKey, new LocalJobStore.Receipt(current, result, conflict,
                        hidden, writerVerified, false)); // No native execution proof is available in this module.
                tx.putJob(current.receipt(result.resultDigest, result.revision, "PROPOSAL".equals(result.state)));
                if ("PENDING".equals(result.state)) return Outcome.PENDING;
                if ("ERROR".equals(result.state)) return Outcome.REMOTE_ERROR;
                if ("CANCELLED".equals(result.state)) return Outcome.REMOTE_CANCELLED;
                if (hidden) return Outcome.HIDDEN_CANDIDATE_STORED;
                if (conflict) return Outcome.CONFLICT_CANDIDATE_STORED;
                if (result.quarantined) return Outcome.QUARANTINED;
                return writerVerified ? Outcome.CANDIDATE_STORED : Outcome.WRITER_UNVERIFIED;
            });
            return reply(outcome, jobId);
        } catch (Halt halt) { return reply(halt.outcome, jobId); }
        catch (DriveException e) { return reply(handle(e), jobId); }
        catch (IllegalArgumentException malformed) { return reply(Outcome.INVALID_RESULT, jobId); }
        catch (LocalJobStore.StoreException failed) { return reply(Outcome.LOCAL_STORE_FAILED, jobId); }
    }

    /** Cached receipt never bypasses current cancellation, source, ownership, policy, or archive gates. */
    public LocalJobStore.Receipt visibleCandidate(String jobId) {
        try {
            return store.transaction(tx -> {
                Job job = tx.job(jobId); checkInTransaction(tx, job, true);
                Source current = tx.source(job.source.sourceKey);
                LocalJobStore.Receipt receipt = tx.receipt(job.source.ownerBinding, jobId, job.resultRevision);
                if (receipt == null || !receipt.proposal || receipt.hidden || current.archived
                        || current.archiveRevision != job.source.archiveRevision) return null;
                return receipt.withConflict(receipt.conflict || job.source.manualConflict(current));
            });
        } catch (Halt | LocalJobStore.StoreException blocked) { return null; }
    }

    public Reply cancel(String jobId) { return stop(jobId, Phase.CANCELLED, Outcome.CANCELLED); }
    /** Local revocation fence first. Remote revokeAccess is a separately approved future native action. */
    public Reply revoke(String jobId) {
        try {
            Reply result = store.transaction(tx -> {
                Job job = tx.job(jobId);
                if (job == null) return reply(Outcome.UNKNOWN_JOB, jobId);
                tx.revokeOwner(job.source.ownerBinding); tx.putJob(job.phase(Phase.REVOKED));
                return reply(Outcome.REVOKED, jobId);
            });
            if (result.outcome == Outcome.REVOKED) authorization.invalidate(AuthorizationPort.State.REVOKED);
            return result;
        } catch (LocalJobStore.StoreException failed) { return reply(Outcome.LOCAL_STORE_FAILED, jobId); }
    }
    private Reply stop(String jobId, Phase phase, Outcome outcome) {
        try {
            return store.transaction(tx -> {
                Job job = tx.job(jobId);
                if (job == null) return reply(Outcome.UNKNOWN_JOB, jobId);
                tx.putJob(job.phase(phase)); return reply(outcome, jobId);
            });
        } catch (LocalJobStore.StoreException failed) { return reply(Outcome.LOCAL_STORE_FAILED, jobId); }
    }
    private Job load(String jobId) { return store.transaction(tx -> tx.job(jobId)); }
    private void check(Job expected) { store.transaction(tx -> { checkInTransaction(tx, tx.job(expected.jobId), false); return null; }); }
    private void checkRetrieval(Job expected) { store.transaction(tx -> { checkInTransaction(tx, tx.job(expected.jobId), true); return null; }); }
    private void checkInTransaction(LocalJobStore.Transaction tx, Job job) {
        checkInTransaction(tx, job, false);
    }
    private void checkInTransaction(LocalJobStore.Transaction tx, Job job, boolean allowManualConflict) {
        if (job == null) throw new Halt(Outcome.UNKNOWN_JOB);
        if (job.phase == Phase.CANCELLED) throw new Halt(Outcome.CANCELLED);
        if (job.phase == Phase.REVOKED) throw new Halt(Outcome.REVOKED);
        if (job.terminal()) throw new Halt(Outcome.STALE);
        checkSourceInTransaction(tx, tx.source(job.source.sourceKey));
        if (!allowManualConflict && tx.source(job.source.sourceKey).archived) throw new Halt(Outcome.POLICY_BLOCKED);
        if (!(allowManualConflict ? job.source.sameIdentity(tx.source(job.source.sourceKey)) : job.source.same(tx.source(job.source.sourceKey)))
                || !job.jobId.equals(tx.activeJobId(job.source.sourceKey))
                || job.authorizationEpoch != authorization.epoch()) throw new Halt(Outcome.STALE);
    }
    private void checkSource(Source source) {
        store.transaction(tx -> {
            checkSourceInTransaction(tx, source);
            Source now = source == null ? null : tx.source(source.sourceKey);
            checkSourceInTransaction(tx, now);
            if (!source.same(now)) throw new Halt(Outcome.STALE);
            if (now.archived) throw new Halt(Outcome.POLICY_BLOCKED);
            return null;
        });
    }
    private void checkSourceInTransaction(LocalJobStore.Transaction tx, Source source) {
        if (authorization.state() == AuthorizationPort.State.NOT_CONFIGURED) throw new Halt(Outcome.NOT_CONFIGURED);
        if (authorization.state() == AuthorizationPort.State.REVOKED) throw new Halt(Outcome.REVOKED);
        if (authorization.state() != AuthorizationPort.State.ACTIVE) throw new Halt(Outcome.AUTHORIZATION_REQUIRED);
        if (source == null || !source.participantConfirmed || source.excluded) throw new Halt(Outcome.POLICY_BLOCKED);
        if (tx.ownerRevoked(source.ownerBinding)) throw new Halt(Outcome.REVOKED);
        if (!source.ownerBinding.equals(authorization.ownerBinding())) throw new Halt(Outcome.REVOKED);
        if (!source.driveAccount.equals(authorization.driveAccount())
                || !source.trustedDriveOwnerId.equals(authorization.driveOwnerPermissionId())) throw new Halt(Outcome.REVOKED);
    }
    private void advance(Job job, Phase next) {
        store.transaction(tx -> {
            Job current = tx.job(job.jobId); checkInTransaction(tx, current);
            // Concurrent retry of the same reserved IDs must never move the checkpoint backwards.
            if (current.phase.ordinal() < next.ordinal()) tx.putJob(current.phase(next));
            return null;
        });
    }
    private void ensureCreate(Job job, FileSpec spec, byte[] bytes) throws DriveException {
        try {
            Metadata created = retry.execute(() -> check(job), () -> transport.create(spec, bytes));
            validateMeta(created, spec);
            if (created.size != bytes.length) throw new Halt(Outcome.FILE_BINDING_MISMATCH);
        } catch (DriveException e) {
            if (e.failure != Failure.CONFLICT) throw e;
            Metadata existing = retry.execute(() -> check(job), () -> transport.metadata(spec.id));
            validateMeta(existing, spec);
            if (spec.role != Role.PRIVATE_FOLDER) {
                if (existing.size != bytes.length) throw new Halt(Outcome.FILE_BINDING_MISMATCH);
                byte[] stored = retry.execute(() -> check(job), () -> transport.download(spec.id, bytes.length)).bytes;
                if (!MessageDigest.isEqual(sha256(stored).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        sha256(bytes).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    throw new Halt(Outcome.FILE_BINDING_MISMATCH);
            }
        }
    }
    private void validateMeta(Metadata m, FileSpec spec) {
        if (m == null || !m.id.equals(spec.id) || !m.name.equals(spec.name) || !m.mimeType.equals(spec.mimeType)
                || !m.ownerBinding.equals(spec.ownerBinding) || m.trashed || !m.isAppAuthorized
                || !m.privateToOwner || (spec.parentId != null && !spec.parentId.equals(m.parentId))
                || m.size < 0 || m.version == null || !m.version.matches("[0-9]+"))
            throw new Halt(Outcome.FILE_BINDING_MISMATCH);
    }
    private static FileSpec spec(Job job, Role role) {
        switch (role) {
            case PRIVATE_FOLDER: return new FileSpec(job.folderId, null, "call-agent-" + job.jobId,
                    DriveTransport.FOLDER_MIME, job.source.trustedDriveOwnerId, role);
            case AUDIO: return new FileSpec(job.audioFileId, job.folderId, "audio.wav", DriveTransport.WAV_MIME, job.source.trustedDriveOwnerId, role);
            case REQUEST: return new FileSpec(job.requestFileId, job.folderId, "request.json", DriveTransport.JSON_MIME, job.source.trustedDriveOwnerId, role);
            case RESULT: return new FileSpec(job.resultFileId, job.folderId, "result.json", DriveTransport.JSON_MIME, job.source.trustedDriveOwnerId, role);
            default: throw new AssertionError(role);
        }
    }
    private Outcome handle(DriveException e) {
        if (e.failure == Failure.UNAUTHORIZED) {
            authorization.invalidate(AuthorizationPort.State.TOKEN_EXPIRED); return Outcome.AUTHORIZATION_REQUIRED;
        }
        if (e.failure == Failure.FORBIDDEN || e.failure == Failure.NOT_FOUND) return Outcome.TRANSPORT_BLOCKED;
        return e.failure == Failure.TRANSIENT ? Outcome.RETRY_LATER : Outcome.TRANSPORT_BLOCKED;
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException("invalid_id");
    }
    private static Reply reply(Outcome outcome, String jobId) { return new Reply(outcome, jobId); }
    private static boolean wav(byte[] b) {
        if (b.length < 44 || !tag(b,0,"RIFF") || !tag(b,8,"WAVE") || le(b,4,4) != b.length-8) return false;
        long align=0, dataSize=0; boolean format=false, data=false; int at=12;
        while (at+8<=b.length) {
            long size=le(b,at+4,4); if (size>b.length-at-8) return false;
            if (tag(b,at,"fmt ")) {
                if (format || size<16 || le(b,at+8,2)!=1) return false; // PCM only for this v0 pilot.
                long channels=le(b,at+10,2), rate=le(b,at+12,4), bits=le(b,at+22,2);
                align=le(b,at+20,2);
                if (channels<1 || channels>2 || rate<8000 || rate>48000 || bits!=16 || align!=channels*2
                        || le(b,at+16,4)!=rate*align) return false;
                format=true;
            } else if (tag(b,at,"data")) {
                if (data || size==0) return false; data=true; dataSize=size;
            }
            long next=at+8+size+(size&1); if (next>b.length) return false; at=(int)next;
        }
        return at==b.length && format && data && dataSize%align==0;
    }
    private static boolean tag(byte[] b,int at,String tag) {
        if (at+4>b.length) return false;
        for (int i=0;i<4;i++) if ((b[at+i]&255)!=tag.charAt(i)) return false;
        return true;
    }
    private static long le(byte[] b,int at,int length) {
        long value=0; for (int i=0;i<length;i++) value|=(long)(b[at+i]&255)<<(8*i); return value;
    }
    public static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : hash) { int n = value & 255; if (n < 16) hex.append('0'); hex.append(Integer.toHexString(n)); }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
