package kr.co.callsummary.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Small bounded canonical journal record. Local private file; no authenticated backup claim. */
final class InitializationLedger {
    static final byte[] MAGIC = {'C','A','L','L','I','N','I','T'};
    static final int MAX_BYTES = 512;
    final InitializationIntent intent;
    final InitializationReport.State state;

    InitializationLedger(InitializationIntent intent, InitializationReport.State state) {
        if (state != InitializationReport.State.PENDING && state != InitializationReport.State.READY
                && state != InitializationReport.State.USED) throw new VaultException("INVALID_INITIALIZATION_STATE");
        this.intent = intent;
        this.state = state;
    }

    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeInt(1);
        out.writeUTF(intent.initializationId);
        out.writeUTF(intent.keyAlias);
        out.writeUTF(state.name());
        out.flush();
        return bytes.toByteArray();
    }

    static InitializationLedger decode(byte[] bytes) {
        try {
            if (bytes.length > MAX_BYTES) throw new IOException("oversize initialization record");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            byte[] magic = new byte[8];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC) || in.readInt() != 1) throw new IOException("unknown initialization record");
            InitializationIntent intent = new InitializationIntent(in.readUTF(), in.readUTF());
            InitializationReport.State state = InitializationReport.State.valueOf(in.readUTF());
            if (in.read() != -1) throw new IOException("trailing initialization data");
            InitializationLedger record = new InitializationLedger(intent, state);
            if (!Arrays.equals(bytes, record.encode())) throw new IOException("noncanonical initialization record");
            return record;
        } catch (Exception error) {
            throw new VaultException("INITIALIZATION_STATE_CORRUPT", error);
        }
    }
}
