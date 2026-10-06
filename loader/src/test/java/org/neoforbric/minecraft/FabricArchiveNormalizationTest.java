package org.neoforbric.minecraft;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class FabricArchiveNormalizationTest {
    @TempDir Path temporary;

    private byte[] type(int value) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "demo/Versioned", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        method.visitCode(); method.visitLdcInsn(value); method.visitInsn(IRETURN); method.visitMaxs(1, 0); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }

    @Test void multiReleaseSnapshotUsesTheJava21ViewAndNeverFollowsManifestClasspath() throws Exception {
        Path original = temporary.resolve("original.jar"), normalized = temporary.resolve("normalized.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        manifest.getMainAttributes().putValue("Class-Path", "unadmitted.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(original), manifest)) {
            for (var entry : Map.of("demo/Versioned.class", type(8),
                    "META-INF/versions/17/demo/Versioned.class", type(17),
                    "META-INF/versions/25/demo/Versioned.class", type(25),
                    "module-info.class", new byte[0]).entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry();
            }
        }
        Archive source = Archive.read(original);
        assertThrows(Failure.class, source::requireSupportedLayout);
        source.requireFabricLayout();
        assertEquals(1, GamePreparation.normalize(original, normalized));
        Archive runtime = Archive.read(normalized); runtime.requireSupportedLayout();
        assertFalse(runtime.names().contains("module-info.class"));
        assertFalse(runtime.names().stream().anyMatch(name -> name.startsWith("META-INF/versions/")));
        AuditLog audit = new AuditLog();
        ClassIndex index = ClassIndex.prepare(List.of(runtime), getClass().getClassLoader(), audit);
        TransformPipeline pipeline = new TransformPipeline(); pipeline.seal(audit);
        try (GameClassLoader loader = new GameClassLoader(index, pipeline, getClass().getClassLoader(), audit)) {
            loader.open();
            assertEquals(17, loader.loadClass("demo.Versioned").getMethod("value").invoke(null));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("demo.Unadmitted"));
        }
    }
}
