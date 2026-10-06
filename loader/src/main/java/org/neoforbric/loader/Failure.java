package org.neoforbric.loader;

public final class Failure extends RuntimeException {
    private final String code;

    public Failure(String code, String message) {
        super(message);
        this.code = code;
    }

    public Failure(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }

    @SuppressWarnings("removal")
    public static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }
}
