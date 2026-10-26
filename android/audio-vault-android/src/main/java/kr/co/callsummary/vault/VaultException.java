package kr.co.callsummary.vault;

public final class VaultException extends RuntimeException {
    private static final long serialVersionUID=1L;
    public final String code;
    public VaultException(String code){super(code);this.code=code;}
    public VaultException(String code,Throwable cause){super(code,cause);this.code=code;}
}
