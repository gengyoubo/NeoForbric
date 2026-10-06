package org.neoforbric.minecraft;

import java.nio.charset.StandardCharsets;
import net.fabricmc.accesswidener.*;
import net.fabricmc.mappingio.tree.MappingTree;
import org.neoforbric.loader.Failure;
import org.objectweb.asm.commons.Remapper;

/** Named AWs are accepted only when their actual symbols already match verified Mojang mappings. */
final class FabricAccessRules {
    private FabricAccessRules() {}
    static byte[] remap(byte[] bytes, MappingTree mappings, Remapper intermediaryRemapper) {
        String[] header = new String(bytes, StandardCharsets.UTF_8).lines().findFirst().orElse("").trim().split("\\s+");
        String namespace = header.length == 3 ? header[2] : "intermediary";
        AccessWidenerWriter writer = new AccessWidenerWriter(AccessWidenerReader.readVersion(bytes));
        AccessWidenerVisitor visitor;
        if (namespace.equals("intermediary")) {
            visitor = new AccessWidenerRemapper(writer, intermediaryRemapper, namespace, "mojang");
        } else if (namespace.equals("named") || namespace.equals("mojang")) {
            int mojang = mappings.getNamespaceId("mojang");
            visitor = new AccessWidenerVisitor() {
                private MappingTree.ClassMapping target(String name) {
                    var type = mappings.getClass(name, mojang);
                    if (type == null) throw unknown(name);
                    return type;
                }
                private Failure unknown(String symbol) {
                    return new Failure("FABRIC_ACCESS_NAMESPACE", "Cannot verify " + namespace + " access rule against Mojang mappings: " + symbol);
                }
                @Override public void visitHeader(String source) { writer.visitHeader("mojang"); }
                @Override public void visitClass(String name, AccessWidenerReader.AccessType access, boolean transitive) {
                    target(name); writer.visitClass(name, access, transitive);
                }
                @Override public void visitField(String owner, String name, String descriptor, AccessWidenerReader.AccessType access, boolean transitive) {
                    if (target(owner).getField(name, descriptor, mojang) == null) throw unknown(owner + "." + name + descriptor);
                    writer.visitField(owner, name, descriptor, access, transitive);
                }
                @Override public void visitMethod(String owner, String name, String descriptor, AccessWidenerReader.AccessType access, boolean transitive) {
                    if (target(owner).getMethod(name, descriptor, mojang) == null) throw unknown(owner + "." + name + descriptor);
                    writer.visitMethod(owner, name, descriptor, access, transitive);
                }
            };
        } else {
            throw new Failure("FABRIC_ACCESS_NAMESPACE", "Unsupported Fabric access-rule namespace: " + namespace);
        }
        try { new AccessWidenerReader(visitor).read(bytes, namespace); }
        catch (AccessWidenerFormatException invalid) {
            throw new Failure(namespace.equals("intermediary") ? "FABRIC_ACCESS_FORMAT" : "FABRIC_ACCESS_NAMESPACE", invalid.getMessage(), invalid);
        }
        return writer.write();
    }
}
