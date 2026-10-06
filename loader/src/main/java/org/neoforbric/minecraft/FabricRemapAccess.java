package org.neoforbric.minecraft;

import java.nio.charset.StandardCharsets;
import net.fabricmc.loader.impl.lib.classtweaker.api.*;
import net.fabricmc.loader.impl.lib.classtweaker.utils.EntryTriple;
import net.fabricmc.loader.impl.lib.classtweaker.visitors.ClassTweakerRemapperVisitor;
import net.fabricmc.mappingio.tree.MappingTree;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;

/** The remapping hierarchy must see the access changes that the runtime will apply. */
final class FabricRemapAccess {
    private final ClassTweaker access = ClassTweaker.newInstance();
    private final MappingTree mappings;
    FabricRemapAccess(MappingTree mappings) { this.mappings = mappings; }
    void read(byte[] bytes) {
        String[] header = new String(bytes, StandardCharsets.UTF_8).lines().findFirst().orElse("").trim().split("\\s+");
        String namespace = header.length == 3 ? header[2] : "intermediary";
        if (namespace.equals("intermediary")) ClassTweakerReader.create(access).read(bytes, namespace);
        else {
            // Validate named rules against Mojang first, then express the analysis
            // view in the same intermediary namespace as the input class graph.
            byte[] mojang = FabricAccessRules.remap(bytes, mappings, new Remapper() {});
            int from = mappings.getNamespaceId("mojang"), to = mappings.getNamespaceId("intermediary");
            Remapper reverse = new Remapper() {
                @Override public String map(String name) {
                    var type = mappings.getClass(name, from);
                    return type == null || type.getName(to) == null ? name : type.getName(to);
                }
                @Override public String mapMethodName(String owner, String name, String descriptor) {
                    var type = mappings.getClass(owner, from);
                    var member = type == null ? null : type.getMethod(name, descriptor, from);
                    return member == null || member.getName(to) == null ? name : member.getName(to);
                }
                @Override public String mapFieldName(String owner, String name, String descriptor) {
                    var type = mappings.getClass(owner, from);
                    var member = type == null ? null : type.getField(name, descriptor, from);
                    return member == null || member.getName(to) == null ? name : member.getName(to);
                }
            };
            ClassTweakerReader.create(new ClassTweakerRemapperVisitor(access, reverse, "mojang", "intermediary")).read(mojang, "mojang");
        }
    }
    ClassVisitor analyze(int version, String name, ClassVisitor next) {
        if (!access.getTargets().contains(name)) return next;
        AccessWidener rules = access.getAccessWidener(name);
        return new ClassVisitor(Opcodes.ASM9, next) {
            private int ownerAccess;
            @Override public void visit(int version, int flags, String owner, String signature, String parent, String[] interfaces) {
                ownerAccess = flags;
                super.visit(version, rules.getClassAccess().apply(flags, owner, flags), owner, signature, parent, interfaces);
            }
            @Override public FieldVisitor visitField(int flags, String field, String descriptor, String signature, Object value) {
                return super.visitField(rules.getFieldAccess(new EntryTriple(name, field, descriptor)).apply(flags, field, ownerAccess), field, descriptor, signature, value);
            }
            @Override public MethodVisitor visitMethod(int flags, String method, String descriptor, String signature, String[] exceptions) {
                return super.visitMethod(rules.getMethodAccess(new EntryTriple(name, method, descriptor)).apply(flags, method, ownerAccess), method, descriptor, signature, exceptions);
            }
        };
    }
}
