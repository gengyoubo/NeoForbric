package org.neoforbric.loader;

import java.util.List;
import org.neoforbric.api.ModDiagnostic;

public final class Failure extends RuntimeException {
    private final String code;
    private final List<ModDiagnostic> diagnostics;

    public Failure(String code, String message) {
        this(code, message, null, List.of());
    }

    public Failure(String code, String message, Throwable cause) {
        this(code, message, cause, List.of());
    }
    public Failure(String code, String message, Throwable cause, List<ModDiagnostic> diagnostics) {
        super(message, cause);
        this.code = code;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public String code() { return code; }
    public List<ModDiagnostic> diagnostics() { return diagnostics; }

    @SuppressWarnings("removal")
    public static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }
}
