package org.neoforbric.loader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveSignatureTest {
    @TempDir Path temporary;
    private void jdkTool(String name, String... args) throws Exception {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        List<String> command = new ArrayList<>(); command.add(Path.of(System.getProperty("java.home"), "bin", name + suffix).toString()); command.addAll(List.of(args));
        Path log = temporary.resolve(name + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail(name + " timed out"); }
        assertEquals(0, process.exitValue(), () -> { try { return Files.readString(log); } catch (IOException error) { return error.toString(); } });
    }
    @Test void signedInputIsVerifiedBeforeItsDerivedArtifactLosesSignatures() throws Exception {
        Path key = temporary.resolve("test-only.p12");
        jdkTool("keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=NeoForbric Test", "-validity", "1", "-keystore", key.toString(), "-storepass", "test-only-password", "-keypass", "test-only-password");
        Path jar = TestJars.jar(temporary.resolve("signed.jar"), Map.of("demo/Signed.class", TestJars.type("demo.Signed")));
        jdkTool("jarsigner", "-keystore", key.toString(), "-storepass", "test-only-password", jar.toString(), "fixture");
        Archive original = Archive.read(jar); AuditLog audit = new AuditLog();
        assertEquals("UNSUPPORTED_LAYOUT", assertThrows(Failure.class, original::requireSupportedLayout).code());
        original.verifyJarSignatures(audit).requireSupportedLayout();
        assertTrue(audit.events().stream().anyMatch(event -> event.type().equals("jar-signature-verified")));
        Map<String, byte[]> damaged = new HashMap<>();
        for (String name : original.names()) if (!name.equals("META-INF/MANIFEST.MF")) damaged.put(name, original.read(name));
        damaged.put("demo/Signed.class", TestJars.text("tampered class bytes"));
        Archive tampered = Archive.read(TestJars.jar(temporary.resolve("damaged.jar"), damaged, new Manifest(new ByteArrayInputStream(original.read("META-INF/MANIFEST.MF")))));
        assertEquals("JAR_SIGNATURE", assertThrows(Failure.class, () -> tampered.verifyJarSignatures(new AuditLog())).code());
    }
}
