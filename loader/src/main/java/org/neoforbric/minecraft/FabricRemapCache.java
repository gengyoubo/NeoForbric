package org.neoforbric.minecraft;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.JarFile;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import org.objectweb.asm.ClassReader;
import org.neoforbric.loader.Archive;

/** Derived files are reused only for identical inputs, mappings, tools and remapping code. */
final class FabricRemapCache {
    private FabricRemapCache() {}

    static void remap(List<GamePreparation.FabricInput> mods, RuntimeInputs inputs, Path cache) throws IOException {
        StringBuilder fingerprint = new StringBuilder("fabric-remap-cache-v1\n");
        List<Path> context = new ArrayList<>(List.of(inputs.game(), inputs.intermediaryGame(), inputs.mappings(), inputs.intermediaryMappings()));
        context.addAll(inputs.libraries());
        try {
            for (Class<?> tool : List.of(TinyRemapper.class, MappingReader.class, ModMetadataParser.class, ClassReader.class))
                context.add(Path.of(tool.getProtectionDomain().getCodeSource().getLocation().toURI()));
            Path code = Path.of(GamePreparation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(code)) {
                try (var paths = Files.list(code.resolve("org/neoforbric/minecraft"))) {
                    for (Path file : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) context.add(file);
                }
            } else context.add(code);
        } catch (java.net.URISyntaxException invalid) { throw new IOException("Cannot fingerprint remapping code", invalid); }
        for (Path path : context) fingerprint.append(hash(path)).append('\n');
        for (var mod : mods) fingerprint.append(hash(mod.source())).append(' ').append(mod.accessRules()).append('\n');
        Path directory = cache.resolve(Archive.sha256(fingerprint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path manifest = directory.resolve("sha256.txt");
        if (Files.isRegularFile(manifest)) {
            List<String> hashes = Files.readAllLines(manifest);
            boolean valid = hashes.size() == mods.size();
            for (int index = 0; valid && index < mods.size(); index++) {
                Path output = directory.resolve(index + ".jar");
                valid = hashes.get(index).matches("[0-9a-f]{64}") && Files.isRegularFile(output) && hash(output).equals(hashes.get(index));
            }
            if (valid) {
                for (int index = 0; index < mods.size(); index++)
                    Files.copy(directory.resolve(index + ".jar"), mods.get(index).output(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Verified Fabric remap cache: " + mods.size() + " inputs");
                return;
            }
            System.out.println("Fabric remap cache differs; rebuilding derived files");
        }
        GamePreparation.remapFabricMods(mods, inputs);
        Files.createDirectories(directory);
        List<String> hashes = new ArrayList<>();
        String transaction = UUID.randomUUID().toString();
        for (int index = 0; index < mods.size(); index++) {
            Path source = mods.get(index).output(), output = directory.resolve(index + ".jar");
            hashes.add(hash(source));
            Path temporary = directory.resolve(index + "." + transaction + ".part");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
        Path temporary = directory.resolve("sha256." + transaction + ".part");
        Files.write(temporary, hashes);
        Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Saved Fabric remap cache: " + mods.size() + " inputs");
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
