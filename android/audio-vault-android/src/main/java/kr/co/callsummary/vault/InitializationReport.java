package kr.co.callsummary.vault;

/** Inspection result only: never authorizes key creation, upload, or app DB updates. */
public final class InitializationReport {
    public enum State { EMPTY, PENDING, READY, USED, LEGACY_REVIEW_REQUIRED, CORRUPT_REVIEW_REQUIRED }
    public final State state;
    public final InitializationIntent intent;
    public final VaultKeys.State keyState;

    InitializationReport(State state, InitializationIntent intent, VaultKeys.State keyState) {
        this.state = state;
        this.intent = intent;
        this.keyState = keyState;
    }
}
