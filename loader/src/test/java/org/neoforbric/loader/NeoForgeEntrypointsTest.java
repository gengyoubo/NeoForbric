package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.neoforbric.neoforge.NeoForgeEntrypoints;
import static org.junit.jupiter.api.Assertions.*;

class NeoForgeEntrypointsTest {
    @TempDir Path root;
    private byte[] type(String name, String id, String descriptor, String dist) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var annotation = writer.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true); annotation.visit("value", id);
        if (dist != null) { var sides = annotation.visitArray("dist"); sides.visitEnum(null,"Lnet/neoforged/api/distmarker/Dist;",dist); sides.visitEnd(); }
        annotation.visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>",descriptor,null,null); constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD,0); constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false); constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0,0); constructor.visitEnd();
        // The scan must not execute this deliberately failing static initializer.
        var initializer = writer.visitMethod(Opcodes.ACC_STATIC,"<clinit>","()V",null,null); initializer.visitCode();
        initializer.visitInsn(Opcodes.ACONST_NULL); initializer.visitInsn(Opcodes.ATHROW); initializer.visitMaxs(0,0); initializer.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    private Archive archive(Map<String,byte[]> classes) throws Exception {
        Map<String,byte[]> entries = new HashMap<>(classes);
        entries.put("META-INF/neoforge.mods.toml",TestJars.text("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"Test\"\n[[mods]]\nmodId=\"sample\"\nversion=\"1\"\n"));
        return Archive.read(TestJars.jar(root.resolve(UUID.randomUUID()+".jar"),entries));
    }
    @Test void scansAllInjectionTypesAndOrdersCommonBeforeSidedEntrypointsWithoutExecutingCode() throws Exception {
        var archive = archive(Map.of("demo/Common.class",type("demo/Common","sample","(Lnet/neoforged/bus/api/IEventBus;Lnet/neoforged/fml/ModContainer;Lnet/neoforged/fml/javafmlmod/FMLModContainer;Lnet/neoforged/api/distmarker/Dist;)V",null),
                "demo/Client.class",type("demo/Client","sample","()V","CLIENT"),"demo/Server.class",type("demo/Server","sample","(Ljava/lang/String;)V","DEDICATED_SERVER")));
        assertEquals(List.of("demo.Common","demo.Client"),NeoForgeEntrypoints.scan(archive,"client").get("sample"));
    }
    @Test void rejectsDanglingIdsAndUnsupportedOrDuplicateInjectionParameters() throws Exception {
        for (String descriptor : List.of("(Ljava/lang/String;)V","(Lnet/neoforged/bus/api/IEventBus;Lnet/neoforged/bus/api/IEventBus;)V")) {
            var archive = archive(Map.of("demo/Bad.class",type("demo/Bad","sample",descriptor,null)));
            assertEquals("NEOFORGE_ENTRYPOINT",assertThrows(Failure.class,()->NeoForgeEntrypoints.scan(archive,"client")).code());
        }
        var dangling = archive(Map.of("demo/Bad.class",type("demo/Bad","undeclared","()V",null)));
        assertEquals("NEOFORGE_ENTRYPOINT",assertThrows(Failure.class,()->NeoForgeEntrypoints.scan(dangling,"client")).code());
    }
}
