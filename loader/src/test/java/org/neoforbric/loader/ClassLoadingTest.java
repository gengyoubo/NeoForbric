package org.neoforbric.loader;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Manifest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.api.*;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class ClassLoadingTest {
    @TempDir Path temporary;
    private static final ClassLoader PARENT = ClassLoadingTest.class.getClassLoader();
    private Archive typeJar(String file, String type) throws Exception {
        return Archive.read(TestJars.jar(temporary.resolve(file), Map.of(type.replace('.', '/') + ".class", TestJars.type(type))));
    }
    private GameClassLoader loader(List<Archive> archives, TransformPipeline pipeline, AuditLog audit) {
        return new GameClassLoader(ClassIndex.prepare(archives, PARENT, audit), pipeline, PARENT, audit);
    }

    @Test void definitionGateAndParentApiIdentityAreEnforced() throws Exception {
        AuditLog audit = new AuditLog();
        TransformPipeline pipeline = new TransformPipeline();
        try (var loader = loader(List.of(typeJar("game.jar", "demo.Target")), pipeline, audit)) {
            assertSame(ModInitializer.class, loader.loadClass(ModInitializer.class.getName()));
            assertEquals("EARLY_DEFINITION", assertThrows(Failure.class, () -> loader.loadClass("demo.Target")).code());
            assertEquals("TRANSFORM_NOT_READY", assertThrows(Failure.class, loader::open).code());
            pipeline.seal(audit); loader.open();
            assertSame(loader, loader.loadClass("demo.Target").getClassLoader());
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.neoforbric.bootstrap.Main"));
        }
    }

    @Test void duplicateClassesProtectedPackagesAndParentContaminationFailBeforeDefine() throws Exception {
        Archive a = typeJar("a.jar", "demo.Target"), b = typeJar("b.jar", "demo.Target");
        assertEquals("DUPLICATE_CLASS", assertThrows(Failure.class, () -> ClassIndex.prepare(List.of(a, b), PARENT, new AuditLog())).code());
        Archive api = typeJar("api.jar", ModInitializer.class.getName());
        assertEquals("PROTECTED_PACKAGE", assertThrows(Failure.class, () -> ClassIndex.prepare(List.of(api), PARENT, new AuditLog())).code());
        try (URLClassLoader contaminated = new URLClassLoader(new URL[]{a.codeSource()}, PARENT)) {
            assertEquals("PARENT_CONTAMINATION", assertThrows(Failure.class, () -> ClassIndex.prepare(List.of(a), contaminated, new AuditLog())).code());
        }
    }

    @Test void identicalPackageAnnotationsMayBeSharedButConflictingMetadataFails() throws Exception {
        String type = "demo.package-info";
        Archive a = typeJar("metadata-a.jar", type), b = typeJar("metadata-b.jar", type);
        AuditLog audit = new AuditLog();
        ClassIndex index = ClassIndex.prepare(List.of(a, b), PARENT, audit);
        assertSame(a, index.entry(type).archive());
        assertEquals(1, audit.events().stream().filter(e -> e.type().equals("identical-package-metadata")).count());
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(a.read("demo/package-info.class")).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public void visitEnd() { visitAnnotation("Ljava/lang/Deprecated;", true).visitEnd(); super.visitEnd(); }
        }, 0);
        Archive conflict = Archive.read(TestJars.jar(temporary.resolve("metadata-conflict.jar"), Map.of("demo/package-info.class", writer.toByteArray())));
        assertEquals("DUPLICATE_CLASS", assertThrows(Failure.class, () -> ClassIndex.prepare(List.of(a, conflict), PARENT, new AuditLog())).code());
    }

    @Test void finalTransformedBytesAreWhatTheVmExecutes() throws Exception {
        AuditLog audit = new AuditLog();
        TransformPipeline pipeline = new TransformPipeline();
        pipeline.add(new TransformPipeline.Transformer() {
            public String id() { return "replace-literal"; }
            public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
                assertArrayEquals(bytes, context.bytecode().original(context.name()));
                ClassWriter output = new ClassWriter(0);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, output) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                            @Override public void visitLdcInsn(Object value) { super.visitLdcInsn(value.equals("before") ? "after" : value); }
                        };
                    }
                }, 0);
                return output.toByteArray();
            }
        });
        try (var loader = loader(List.of(typeJar("game.jar", "demo.Target")), pipeline, audit)) {
            pipeline.seal(audit); loader.open();
            Class<?> target = loader.loadClass("demo.Target");
            assertEquals("after", target.getMethod("value").invoke(null));
            assertEquals(1, audit.events().stream().filter(e -> e.type().equals("transform")).count());
            assertThrows(Failure.class, () -> pipeline.add(new Identity()));
        }
    }

    @Test void concurrentRequestsDefineAndTransformExactlyOnce() throws Exception {
        AtomicInteger transformed = new AtomicInteger();
        TransformPipeline pipeline = new TransformPipeline();
        pipeline.add(new TransformPipeline.Transformer() {
            public String id() { return "count"; }
            public byte[] transform(TransformPipeline.Context context, byte[] bytes) { transformed.incrementAndGet(); return bytes; }
        });
        AuditLog audit = new AuditLog();
        try (var loader = loader(List.of(typeJar("game.jar", "demo.Target")), pipeline, audit);
             var executor = Executors.newFixedThreadPool(2)) {
            pipeline.seal(audit); loader.open();
            CountDownLatch start = new CountDownLatch(1);
            Callable<Class<?>> load = () -> { start.await(); return loader.loadClass("demo.Target"); };
            Future<Class<?>> a = executor.submit(load), b = executor.submit(load);
            start.countDown();
            assertSame(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertEquals(1, transformed.get());
            assertEquals(1, audit.events().stream().filter(e -> e.type().equals("class-defined")).count());
        }
    }

    @Test void generatedClassesNeedAnExplicitProviderAndStillRespectNameAndPackageBoundaries() throws Exception {
        Archive owner = typeJar("generator-owner.jar", "demo.Generator");
        AuditLog audit = new AuditLog(); TransformPipeline pipeline = new TransformPipeline();
        try (var loader = loader(List.of(owner), pipeline, audit)) {
            loader.generatedClasses(name -> name.equals("demo.Generated") ? new GameClassLoader.Generated(TestJars.type(name), owner, "fixture-generator") : null);
            pipeline.seal(audit); loader.open();
            assertSame(loader, loader.loadClass("demo.Generated").getClassLoader());
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("demo.Unregistered"));
            assertEquals(1, audit.events().stream().filter(event -> event.type().equals("generated-class-owner")).count());
            assertThrows(Failure.class, () -> loader.generatedClasses(name -> null));
        }
        for (String name : List.of("demo.Invalid", "org.neoforbric.Untrusted")) {
            TransformPipeline invalidPipeline = new TransformPipeline(); AuditLog invalidAudit = new AuditLog();
            try (var loader = loader(List.of(owner), invalidPipeline, invalidAudit)) {
                loader.generatedClasses(requested -> new GameClassLoader.Generated(TestJars.type("demo.Other"), owner, "broken-generator"));
                invalidPipeline.seal(invalidAudit); loader.open();
                assertEquals(name.startsWith("org.neoforbric.") ? "PROTECTED_PACKAGE" : "CLASS_NAME_MISMATCH", assertThrows(Failure.class, () -> loader.loadClass(name)).code());
                assertEquals("INSTANCE_TAINTED", assertThrows(Failure.class, () -> loader.loadClass("demo.Generator")).code());
            }
        }
    }

    @Test void failedOrReentrantTransformationPoisonsTheDomain() throws Exception {
        AuditLog audit = new AuditLog();
        TransformPipeline pipeline = new TransformPipeline();
        GameClassLoader[] targetLoader = new GameClassLoader[1];
        pipeline.add(new TransformPipeline.Transformer() {
            public String id() { return "illegal-class-lookup"; }
            public byte[] transform(TransformPipeline.Context context, byte[] bytes) throws Exception {
                targetLoader[0].loadClass(context.name());
                return bytes;
            }
        });
        try (var loader = loader(List.of(typeJar("game.jar", "demo.Target")), pipeline, audit)) {
            targetLoader[0] = loader;
            pipeline.seal(audit); loader.open();
            Failure failure = assertThrows(Failure.class, () -> loader.loadClass("demo.Target"));
            assertEquals("TRANSFORM_FAILED", failure.code());
            assertEquals("REENTRANT_DEFINITION", ((Failure) failure.getCause()).code());
            assertEquals("INSTANCE_TAINTED", assertThrows(Failure.class, () -> loader.loadClass("demo.Target")).code());
            assertFalse(audit.events().stream().anyMatch(e -> e.type().equals("class-defined")));
        }
    }

    @Test void transformOrderingRequiresACompleteAcyclicPlan() {
        TransformPipeline pipeline = new TransformPipeline();
        pipeline.add(new Identity() { @Override public Set<String> after() { return Set.of("missing"); } });
        assertEquals("ORDER_MISSING", assertThrows(Failure.class, () -> pipeline.seal(new AuditLog())).code());
        TransformPipeline cycle = new TransformPipeline();
        cycle.add(new Identity() { public String id() { return "a"; } public Set<String> after() { return Set.of("b"); } });
        cycle.add(new Identity() { public String id() { return "b"; } public Set<String> after() { return Set.of("a"); } });
        assertEquals("ORDER_CYCLE", assertThrows(Failure.class, () -> cycle.seal(new AuditLog())).code());
    }

    @Test void transformerErrorsAndRenamedOutputCannotFallBackToOriginalBytes() throws Exception {
        for (boolean rename : List.of(false, true)) {
            TransformPipeline pipeline = new TransformPipeline();
            pipeline.add(new TransformPipeline.Transformer() {
                public String id() { return "broken-rule"; }
                public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
                    if (rename) return TestJars.type("demo.WrongName");
                    throw new AssertionError("rule assertion");
                }
            });
            AuditLog audit = new AuditLog();
            try (var loader = loader(List.of(typeJar("broken.jar", "demo.Target")), pipeline, audit)) {
                pipeline.seal(audit); loader.open();
                Failure failure = assertThrows(Failure.class, () -> loader.loadClass("demo.Target"));
                assertEquals("TRANSFORM_FAILED", failure.code());
                if (rename) assertEquals("CLASS_NAME_MISMATCH", ((Failure) failure.getCause()).code());
                else assertInstanceOf(AssertionError.class, failure.getCause());
                assertEquals("INSTANCE_TAINTED", assertThrows(Failure.class, () -> loader.loadClass("demo.Target")).code());
                assertFalse(audit.events().stream().anyMatch(e -> e.type().equals("class-defined")));
                assertEquals("broken-rule", audit.events().stream().filter(e -> e.type().equals("transform-failed")).findFirst().orElseThrow().details().get("rule"));
            }
        }
    }

    @Test void servicesAggregateAcrossArchivesUsingTheSameSpiClass() throws Exception {
        String descriptor = "META-INF/services/" + ProbeService.class.getName();
        List<Archive> archives = new ArrayList<>();
        for (String name : List.of("demo.ServiceA", "demo.ServiceB")) archives.add(Archive.read(TestJars.jar(temporary.resolve(name + ".jar"),
                Map.of(name.replace('.', '/') + ".class", TestJars.service(name), descriptor, TestJars.text(name + "\n")))));
        AuditLog audit = new AuditLog(); TransformPipeline pipeline = new TransformPipeline();
        try (var loader = loader(archives, pipeline, audit)) {
            pipeline.seal(audit); loader.open();
            assertEquals(List.of("demo.ServiceA", "demo.ServiceB"), ServiceLoader.load(ProbeService.class, loader).stream().map(p -> p.get().name()).toList());
        }
    }

    @Test void archiveSnapshotDoesNotChangeAfterTheFileIsReplaced() throws Exception {
        Archive original = typeJar("game.jar", "demo.Original");
        TestJars.jar(original.path(), Map.of("demo/Replacement.class", TestJars.type("demo.Replacement")));
        AuditLog audit = new AuditLog(); TransformPipeline pipeline = new TransformPipeline();
        try (var loader = loader(List.of(original), pipeline, audit)) {
            pipeline.seal(audit); loader.open();
            assertSame(loader, loader.loadClass("demo.Original").getClassLoader());
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("demo.Replacement"));
            assertArrayEquals(original.read("demo/Original.class"), loader.getResource("demo/Original.class").openStream().readAllBytes());
        }
    }

    @Test void unsupportedJarLayoutsAreRejectedInsteadOfSilentlyFlattened() throws Exception {
        for (String attribute : List.of("Class-Path", "Automatic-Module-Name", "Multi-Release")) {
            Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue(attribute, attribute.equals("Class-Path") ? "other.jar" : attribute.equals("Automatic-Module-Name") ? "example" : "true");
            Archive archive = Archive.read(TestJars.jar(temporary.resolve("unsupported.jar"), Map.of("demo/Target.class", TestJars.type("demo.Target")), manifest));
            assertEquals("UNSUPPORTED_LAYOUT", assertThrows(Failure.class, archive::requireSupportedLayout).code());
        }
        for (String entry : List.of("META-INF/SAMPLE.SF", "META-INF/SIG-CUSTOM", "lib/nested.jar", "lib/nested.JAR", "module-info.class", "META-INF/versions/21/demo/Target.class")) {
            Archive archive = Archive.read(TestJars.jar(temporary.resolve("entry.jar"), Map.of(entry, TestJars.text("unsupported"))));
            assertEquals("UNSUPPORTED_LAYOUT", assertThrows(Failure.class, archive::requireSupportedLayout).code());
        }
    }

    @Test void sealedPackagesOnlyAcceptClassesFromTheirDeclaringArchive() throws Exception {
        Manifest sealed = new Manifest(); sealed.getMainAttributes().putValue("Manifest-Version", "1.0"); sealed.getMainAttributes().putValue("Sealed", "true");
        Archive owner = Archive.read(TestJars.jar(temporary.resolve("sealed.jar"), Map.of("demo/sealed/Target.class", TestJars.type("demo.sealed.Target")), sealed));
        Archive intruder = typeJar("intruder.jar", "demo.sealed.Intruder");
        owner.requireSupportedLayout();
        AuditLog audit = new AuditLog(); TransformPipeline pipeline = new TransformPipeline();
        try (var loader = loader(List.of(owner, intruder), pipeline, audit)) {
            pipeline.seal(audit); loader.open();
            loader.loadClass("demo.sealed.Target");
            assertTrue(loader.getDefinedPackage("demo.sealed").isSealed());
            assertEquals("PACKAGE_SEALED", assertThrows(Failure.class, () -> loader.loadClass("demo.sealed.Intruder")).code());
        }
    }

    private static class Identity implements TransformPipeline.Transformer {
        public String id() { return "identity"; }
        public byte[] transform(TransformPipeline.Context context, byte[] bytes) { return bytes; }
    }
}
