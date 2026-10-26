package kr.callagent.drive;

/** No account/session enumeration, no token extraction, no login at module construction. */
public interface AuthorizationPort {
    String DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    enum State { NOT_CONFIGURED, CONSENT_REQUIRED, ACTIVE, TOKEN_EXPIRED, REVOKED }
    State state();
    /** Opaque stable owner binding from an approved flow, never an email guess. */
    String ownerBinding();
    String driveAccount();
    /** Obtained from an approved Drive about response; separate from wire ownerId claims. */
    String driveOwnerPermissionId();
    long epoch();
    /** Invalid credentials halt work. Only a future explicit authorized action may authorize again. */
    void invalidate(State reason);

    final class NotConfigured implements AuthorizationPort {
        @Override public State state() { return State.NOT_CONFIGURED; }
        @Override public String ownerBinding() { return ""; }
        @Override public String driveAccount() { return ""; }
        @Override public String driveOwnerPermissionId() { return ""; }
        @Override public long epoch() { return 0; }
        @Override public void invalidate(State reason) { /* remains not configured */ }
    }
}
