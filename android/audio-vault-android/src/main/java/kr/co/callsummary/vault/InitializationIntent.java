package kr.co.callsummary.vault;

import java.util.Objects;

/** App-local first-use operation identity. Not a native account/token or worker wire. */
public final class InitializationIntent {
    public final String initializationId;
    public final String keyAlias;

    public InitializationIntent(String initializationId, String keyAlias) {
        AudioBinding.id(initializationId);
        AudioBinding.alias(keyAlias);
        this.initializationId = initializationId;
        this.keyAlias = keyAlias;
    }

    public boolean equals(Object other) {
        if (!(other instanceof InitializationIntent)) return false;
        InitializationIntent intent = (InitializationIntent) other;
        return initializationId.equals(intent.initializationId) && keyAlias.equals(intent.keyAlias);
    }

    public int hashCode() { return Objects.hash(initializationId, keyAlias); }
}
