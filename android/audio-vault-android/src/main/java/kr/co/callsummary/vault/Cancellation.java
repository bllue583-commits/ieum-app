package kr.co.callsummary.vault;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Cancellation {
    private final AtomicBoolean cancelled=new AtomicBoolean();
    public void cancel(){cancelled.set(true);}
    public void check(){if(cancelled.get() || Thread.currentThread().isInterrupted())throw new VaultException("CANCELLED");}
}
