package org.neoforbric.fabric;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweakerReader;
import net.fabricmc.loader.impl.transformer.FabricTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.bootstrap.LaunchOptions;
import org.neoforbric.loader.AuditLog;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class FabricGameAccessTest {
    @TempDir Path directory;

    @Test void fabricWidensMojangGameClassesForForeignPackageCallers() throws Exception {
        // Fabric's launcher and facade are process singletons; keep the rest of the test suite independent.
        String classpath = System.getProperty("loader.runtimeClasspath") + File.pathSeparator
                + Path.of(FabricGameAccessTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path output = directory.resolve("access.txt");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath, Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Access transformer JVM did not terminate");
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertTrue(Files.readString(output).contains("MOJANG_ACCESS_OK"));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static final class Probe extends ClassLoader {
        private static final String INNER = "com/mojang/blaze3d/vertex/AccessProbe$Double";
        public static void main(String[] args) throws Exception {
            LaunchOptions options = new LaunchOptions(Path.of("game.jar"), Path.of("mods"), "demo.Main", "client", Path.of("audit.json"), false, java.util.List.of());
            new NeoFabricLauncher(EnvType.CLIENT, options.mainClass(), new AuditLog());
            NeoGameProvider provider = new NeoGameProvider(options);
            FabricLoaderImpl.INSTANCE.setGameProvider(provider);
            ClassTweakerReader.create(FabricLoaderImpl.INSTANCE.getClassTweaker()).read(("accessWidener v2 mojang\n"
                    + "accessible class " + INNER + "\naccessible field " + INNER + " first Ljava/lang/String;\n")
                    .getBytes(StandardCharsets.UTF_8), "mojang");
            Probe domain = new Probe();
            byte[] original = inner(INNER);
            domain.define(FabricTransformer.transform(false, EnvType.CLIENT, INNER.replace('/', '.'), original));
            Class<?> caller = domain.define(caller());
            Object instance = domain.loadClass(INNER.replace('/', '.')).getConstructor().newInstance();
            if (!"widened".equals(caller.getMethod("read", Object.class).invoke(null, instance))) throw new AssertionError("Wrong field value");
            // A different package-private class with no AW remains inaccessible.
            String untouched = INNER + "Unwidened";
            Class<?> plain = domain.define(FabricTransformer.transform(false, EnvType.CLIENT, untouched.replace('/', '.'), inner(untouched)));
            try {
                plain.getConstructor().newInstance();
                throw new AssertionError("Unrequested class was widened");
            } catch (IllegalAccessException expected) { /* No AW target: preserve the original access. */ }
            for (String prefix : new String[]{"net.minecraft.", "com.mojang.minecraft.", "com.mojang.rubydung.", "com.mojang.blaze3d.", "com.mojang.renderpearl.", "com.mojang.math.", "com.mojang.realmsclient."})
                if (!provider.getBuiltinTransforms(prefix + "Target").contains(net.fabricmc.loader.impl.game.GameProvider.BuiltinTransform.CLASS_TWEAKS)) throw new AssertionError(prefix);
            for (String prefix : new String[]{"com.mojang.serialization.", "com.mojang.authlib.", "software.bernie.", "com.mojang.blaze3dx."})
                if (provider.getBuiltinTransforms(prefix + "Target").contains(net.fabricmc.loader.impl.game.GameProvider.BuiltinTransform.CLASS_TWEAKS)) throw new AssertionError(prefix);
            System.out.println("MOJANG_ACCESS_OK");
        }
        private Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        private static byte[] inner(String name) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(V21, 0, name, null, "java/lang/Object", null);
            writer.visitInnerClass(name, "com/mojang/blaze3d/vertex/AccessProbe", "Double", ACC_STATIC);
            writer.visitField(ACC_PRIVATE, "first", "Ljava/lang/String;", null, null).visitEnd();
            MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode(); constructor.visitVarInsn(ALOAD, 0);
            constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitVarInsn(ALOAD, 0); constructor.visitLdcInsn("widened");
            constructor.visitFieldInsn(PUTFIELD, name, "first", "Ljava/lang/String;");
            constructor.visitInsn(RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
            writer.visitEnd(); return writer.toByteArray();
        }
        private static byte[] caller() {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(V21, ACC_PUBLIC, "software/bernie/probe/Caller", null, "java/lang/Object", null);
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
            method.visitCode(); method.visitVarInsn(ALOAD, 0); method.visitTypeInsn(CHECKCAST, INNER);
            method.visitFieldInsn(GETFIELD, INNER, "first", "Ljava/lang/String;");
            method.visitInsn(ARETURN); method.visitMaxs(0, 0); method.visitEnd();
            writer.visitEnd(); return writer.toByteArray();
        }
    }
}
