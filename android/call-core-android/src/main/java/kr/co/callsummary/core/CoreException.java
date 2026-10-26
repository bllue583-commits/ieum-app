package kr.co.callsummary.core;

public final class CoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final String code;
    public CoreException(String code) { super(code); this.code = code; }
    public CoreException(String code, Throwable cause) { super(code, cause); this.code = code; }
}
