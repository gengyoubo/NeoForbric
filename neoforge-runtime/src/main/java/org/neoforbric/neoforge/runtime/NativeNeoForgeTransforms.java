package org.neoforbric.neoforge.runtime;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.neoforged.fml.common.asm.enumextension.RuntimeEnumExtender;
import cpw.mods.modlauncher.api.*;
import java.util.*;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

/** A passive enum tool in the kernel pipeline; it never creates or launches a loader. */
public final class NativeNeoForgeTransforms {
    private static RuntimeEnumExtender enums;
    private static Map<String, List<ITransformer<?>>> scripts = Map.of();
    private NativeNeoForgeTransforms() {}
    public static void initialize(List<ModFileInfo> files) throws Exception {
        enums = new RuntimeEnumExtender();
        var loader = Class.forName("net.neoforged.fml.loading.CoreModScriptLoader");
        var method = loader.getDeclaredMethod("loadCoreModScripts", List.class); method.setAccessible(true);
        @SuppressWarnings("unchecked") var transformers = (List<ITransformer<?>>)method.invoke(null, files.stream().filter(file -> !file.getFile().getCoreMods().isEmpty()).toList());
        Map<String, List<ITransformer<?>>> byClass = new HashMap<>();
        for (var transformer : transformers) for (var target : transformer.targets())
            byClass.computeIfAbsent(target.className().replace('/', '.'), ignored -> new ArrayList<>()).add(transformer);
        scripts = Map.copyOf(byClass);
        System.out.println("NEOFORGE_TRANSFORMS_READY coremods=" + transformers.size());
    }
    public static byte[] transform(String name, byte[] bytes) {
        var phase = ILaunchPluginService.Phase.BEFORE; Type type = Type.getObjectType(name.replace('.', '/'));
        boolean extend = enums != null && enums.handlesClass(type, false).contains(phase);
        List<ITransformer<?>> coremods = scripts.getOrDefault(name, List.of());
        if (!extend && coremods.isEmpty()) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        ITransformerVotingContext context = context(name, node, bytes);
        for (var transformer : new LinkedHashSet<>(coremods)) {
            if (transformer.castVote(context) != TransformerVoteResult.YES) continue;
            for (var target : transformer.targets()) if (target.className().replace('/', '.').equals(name)) {
                if (target.targetType() == TargetType.CLASS || target.targetType() == TargetType.PRE_CLASS) node = apply(transformer, node, context);
                else if (target.targetType() == TargetType.METHOD) {
                    for (int i = 0; i < node.methods.size(); i++) { var member = node.methods.get(i);
                        if (member.name.equals(target.elementName()) && member.desc.equals(target.elementDescriptor())) node.methods.set(i, apply(transformer, member, context));
                    }
                } else if (target.targetType() == TargetType.FIELD) {
                    for (int i = 0; i < node.fields.size(); i++) { var member = node.fields.get(i);
                        if (member.name.equals(target.elementName())) node.fields.set(i, apply(transformer, member, context));
                    }
                } else throw new IllegalStateException("Unsupported coremod target " + target);
            }
        }
        if (extend) enums.processClass(phase, node, type);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private static <T> T apply(ITransformer transformer, T node, ITransformerVotingContext context) { return (T)transformer.transform(node, context); }
    private static ITransformerVotingContext context(String name, ClassNode node, byte[] bytes) {
        return new ITransformerVotingContext() {
            public String getClassName() { return name; }
            public boolean doesClassExist() { return true; }
            public byte[] getInitialClassSha256() { try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); } catch (Exception error) { throw new IllegalStateException(error); } }
            public List<ITransformerActivity> getAuditActivities() { return List.of(); }
            public String getReason() { return "classloading"; }
            public boolean applyFieldPredicate(FieldPredicate predicate) { return node.fields.stream().anyMatch(field -> predicate.test(field.access, field.name, field.desc, field.signature, field.value)); }
            public boolean applyMethodPredicate(MethodPredicate predicate) { return node.methods.stream().anyMatch(method -> predicate.test(method.access, method.name, method.desc, method.signature, method.exceptions.toArray(String[]::new))); }
            public boolean applyClassPredicate(ClassPredicate predicate) { return predicate.test(node.version, node.access, node.name, node.signature, node.superName, node.interfaces.toArray(String[]::new)); }
            public boolean applyInstructionPredicate(InsnPredicate predicate) { throw new UnsupportedOperationException("Coremod instruction voting requires an operand adapter"); }
        };
    }
}
