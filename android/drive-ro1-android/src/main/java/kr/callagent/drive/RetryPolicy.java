package kr.callagent.drive;

import kr.callagent.drive.DriveTransport.DriveException;
import kr.callagent.drive.DriveTransport.Failure;

/** Bounded backoff; only documented rate-limit/transient failures qualify. */
public final class RetryPolicy {
    public interface Sleeper { void delay(long millis) throws InterruptedException; }
    public interface Jitter { long below(long upperExclusive); }
    public interface Check { void run(); }
    public interface Call<T> { T run() throws DriveException; }
    private final Sleeper sleeper;
    private final Jitter jitter;
    private final int maxAttempts;
    public RetryPolicy(Sleeper sleeper, Jitter jitter, int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 5) throw new IllegalArgumentException("attempts");
        this.sleeper = sleeper; this.jitter = jitter; this.maxAttempts = maxAttempts;
    }
    public <T> T execute(Check check, Call<T> call) throws DriveException {
        for (int attempt = 0; ; attempt++) {
            check.run();
            try { T result = call.run(); check.run(); return result; }
            catch (DriveException e) {
                if (e.failure != Failure.TRANSIENT || attempt + 1 >= maxAttempts) throw e;
                long backoff = Math.min(30_000L, (1_000L << attempt));
                long noise = jitter.below(1_000L);
                if (noise < 0 || noise >= 1_000L) throw new IllegalStateException("jitter_range");
                long delay = Math.min(60_000L, Math.max(backoff + noise, e.retryAfterMillis));
                check.run();
                try { sleeper.delay(delay); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DriveException(Failure.TRANSIENT);
                }
            }
        }
    }
}
