package org.neoforbric.loader;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import static org.objectweb.asm.Opcodes.*;

final class TestJars {
    static Path jar(Path path, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        return jar(path, entries, manifest);
    }
    static Path jar(Path path, Map<String, byte[]> entries, Manifest manifest) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
    static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static byte[] metadata(String id, String version, String entrypoint, Map<String, String> dependencies, String side) {
        return text(new Gson().toJson(Map.of("schemaVersion", 1, "id", id, "version", version, "entrypoint", entrypoint,
                "depends", dependencies, "environment", side)));
    }
    static Discovery.Candidate candidate(Path path, String id, Map<String, String> dependencies) throws IOException {
        return candidate(path, id, "1.0.0", dependencies, "*");
    }
    static Discovery.Candidate candidate(Path path, String id, String version, Map<String, String> dependencies, String side) throws IOException {
        Archive archive = Archive.read(jar(path, Map.of("neoforbric.mod.json", metadata(id, version, "demo." + id, dependencies, side))));
        return new Discovery.Candidate(archive, Metadata.read(archive).getFirst());
    }
    private static ClassWriter writer(String name, String... interfaces) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", interfaces);
        MethodVisitor ctor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0); ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        return writer;
    }
    static byte[] type(String name) {
        ClassWriter writer = writer(name);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
        method.visitCode(); method.visitLdcInsn("before"); method.visitInsn(ARETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    static byte[] initializer(String name, String property, boolean fail) {
        ClassWriter writer = writer(name, "org/neoforbric/api/ModInitializer");
        MethodVisitor clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode(); setProperty(clinit, property, "initialized"); clinit.visitInsn(RETURN); clinit.visitMaxs(0, 0); clinit.visitEnd();
        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "onInitialize", "()V", null, null);
        init.visitCode();
        if (fail) {
            init.visitTypeInsn(NEW, "java/lang/IllegalStateException"); init.visitInsn(DUP); init.visitLdcInsn("fixture failure");
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false); init.visitInsn(ATHROW);
        } else { setProperty(init, property + ".complete", "yes"); init.visitInsn(RETURN); }
        init.visitMaxs(0, 0); init.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    static byte[] main(String name, String property) {
        ClassWriter writer = writer(name);
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode(); setProperty(main, property, "yes"); main.visitInsn(RETURN); main.visitMaxs(0, 0); main.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    static byte[] argumentMain(String name, String property) {
        ClassWriter writer = writer(name);
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode(); main.visitLdcInsn(property); main.visitLdcInsn(","); main.visitVarInsn(ALOAD, 0);
        main.visitMethodInsn(INVOKESTATIC, "java/lang/String", "join", "(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKESTATIC, "java/lang/System", "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        main.visitInsn(POP); main.visitInsn(RETURN); main.visitMaxs(0, 0); main.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    static byte[] service(String name) {
        ClassWriter writer = writer(name, "org/neoforbric/api/ProbeService");
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "name", "()Ljava/lang/String;", null, null);
        method.visitCode(); method.visitLdcInsn(name); method.visitInsn(ARETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static void setProperty(MethodVisitor method, String key, String value) {
        method.visitLdcInsn(key); method.visitLdcInsn(value);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/System", "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitInsn(POP);
    }
}
