package org.neoforbric.loader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveDuplicateTest {
    @TempDir Path root;
    private Path duplicates(String first, String second, byte[] a, byte[] b) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(first)); zip.write(a); zip.closeEntry();
            zip.putNextEntry(new ZipEntry(second)); zip.write(b); zip.closeEntry();
        }
        byte[] bytes = buffer.toByteArray(), from = second.getBytes(StandardCharsets.UTF_8), to = first.getBytes(StandardCharsets.UTF_8);
        assertEquals(from.length, to.length);
        for (int i = 0; i <= bytes.length - from.length; i++)
            if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) System.arraycopy(to, 0, bytes, i, to.length);
        Path path = root.resolve("duplicate.jar"); Files.write(path, bytes); return path;
    }
    @Test void coalescesIdenticalResourcesButRejectsConflictingResourcesAndAllClasses() throws Exception {
        byte[] license = TestJars.text("Apache License");
        assertArrayEquals(license, Archive.read(duplicates("META-INF/LICENSE.one", "META-INF/LICENSE.two", license, license)).read("META-INF/LICENSE.one"));
        assertEquals("DUPLICATE_ENTRY", assertThrows(Failure.class, () -> Archive.read(duplicates("META-INF/LICENSE.one", "META-INF/LICENSE.two", license, TestJars.text("Other")))).code());
        assertEquals("DUPLICATE_ENTRY", assertThrows(Failure.class, () -> Archive.read(duplicates("demo/One.class", "demo/Two.class", license, license))).code());
    }
    @Test void exposesPerPackageJarVersionToLanguageProviders() throws Exception {
        var manifest = new java.util.jar.Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "1.0");
        var attributes = new java.util.jar.Attributes(); attributes.putValue("Implementation-Version", "2.0"); manifest.getEntries().put("demo/", attributes);
        Archive archive = Archive.read(TestJars.jar(root.resolve("version.jar"), Map.of("demo/PackageVersion.class", TestJars.type("demo.PackageVersion")), manifest));
        AuditLog audit = new AuditLog(); var pipeline = new TransformPipeline(); pipeline.seal(audit);
        try (var loader = new GameClassLoader(ClassIndex.prepare(List.of(archive), getClass().getClassLoader(), audit), pipeline, getClass().getClassLoader(), audit)) {
            loader.open(); assertEquals("2.0", loader.loadClass("demo.PackageVersion").getPackage().getImplementationVersion());
        }
    }
}
