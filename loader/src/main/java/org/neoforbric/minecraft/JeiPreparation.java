package org.neoforbric.minecraft;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.neoforbric.loader.*;

/** Fixed official test inputs; never installs or removes anything in the user's mods directory. */
public final class JeiPreparation {
    private JeiPreparation() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new Failure("ARGUMENTS", "JeiPreparation <test-directory>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("downloads")); Files.createDirectories(root.resolve("mods"));
        JsonObject lock;
        try (InputStream in = JeiPreparation.class.getResourceAsStream("jei-inputs.json")) {
            if (in == null) throw new IOException("Missing JEI input lock");
            lock = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (JsonElement element : lock.getAsJsonArray("inputs")) {
            JsonObject input = element.getAsJsonObject(); String filename = input.get("filename").getAsString();
            if (!Path.of(filename).getFileName().toString().equals(filename)) throw new Failure("INPUT_PATH", filename);
            Path source = GamePreparation.fetch(root.resolve("downloads").resolve(filename), input.get("url").getAsString(), input.get("sha512").getAsString(), "SHA-512");
            if (!Archive.sha256(Files.readAllBytes(source)).equals(input.get("sha256").getAsString())) throw new Failure("INPUT_CHECKSUM", source.toString());
            Files.copy(source, root.resolve("mods").resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Verified JEI test input: " + input.get("id").getAsString() + " " + input.get("version").getAsString());
        }
    }
}
