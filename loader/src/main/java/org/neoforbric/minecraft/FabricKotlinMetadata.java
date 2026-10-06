package org.neoforbric.minecraft;

import java.util.*;
import kotlin.Metadata;
import kotlin.metadata.*;
import kotlin.metadata.jvm.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.neoforbric.loader.Failure;
import static kotlin.metadata.jvm.JvmExtensionsKt.*;

/** Keep Kotlin reflection's type graph and JVM signatures aligned with remapped bytecode. */
final class FabricKotlinMetadata {
    private final Remapper mapper;
    private FabricKotlinMetadata(Remapper mapper) { this.mapper = mapper; }

    static byte[] remap(byte[] bytes, Remapper mapper) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        AnnotationNode annotation = metadata(node.visibleAnnotations);
        if (annotation == null) annotation = metadata(node.invisibleAnnotations);
        if (annotation == null) return bytes;
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2)
            fields.put((String) annotation.values.get(i), annotation.values.get(i + 1));
        int[] version = integers(fields.get("mv"));
        if (version.length < 2 || version[0] == 1 && version[1] < 4) return bytes;
        Metadata header = JvmMetadataUtil.Metadata((Integer) fields.get("k"), version,
                strings(fields.get("d1")), strings(fields.get("d2")), (String) fields.get("xs"),
                (String) fields.get("pn"), (Integer) fields.get("xi"));
        try {
            KotlinClassMetadata parsed = KotlinClassMetadata.readStrict(header);
            FabricKotlinMetadata transform = new FabricKotlinMetadata(mapper);
            switch (parsed) {
                case KotlinClassMetadata.Class klass -> transform.klass(klass.getKmClass());
                case KotlinClassMetadata.FileFacade file -> transform.pkg(file.getKmPackage());
                case KotlinClassMetadata.MultiFileClassPart part -> {
                    transform.pkg(part.getKmPackage());
                    part.setFacadeClassName(mapper.mapType(part.getFacadeClassName()));
                }
                case KotlinClassMetadata.MultiFileClassFacade facade -> facade.getPartClassNames().replaceAll(mapper::mapType);
                case KotlinClassMetadata.SyntheticClass synthetic -> {
                    if (synthetic.getKmLambda() == null) return bytes;
                    transform.function(synthetic.getKmLambda().getFunction(), node.name);
                }
                default -> { return bytes; }
            }
            Metadata mapped = parsed.write();
            annotation.values = new ArrayList<>();
            annotation.visit("k", mapped.k()); annotation.visit("mv", mapped.mv());
            annotation.values.addAll(List.of("d1", new ArrayList<>(Arrays.asList(mapped.d1())),
                    "d2", new ArrayList<>(Arrays.asList(mapped.d2()))));
            annotation.visit("xs", mapped.xs()); annotation.visit("pn", mapped.pn()); annotation.visit("xi", mapped.xi());
            ClassWriter output = new ClassWriter(0); node.accept(output); return output.toByteArray();
        } catch (RuntimeException invalid) {
            throw new Failure("KOTLIN_METADATA", "Cannot remap Kotlin metadata for " + node.name, invalid);
        }
    }
    private static AnnotationNode metadata(List<AnnotationNode> annotations) {
        if (annotations != null) for (AnnotationNode annotation : annotations)
            if (annotation.desc.equals("Lkotlin/Metadata;")) return annotation;
        return null;
    }
    private static int[] integers(Object value) {
        if (value instanceof int[] numbers) return numbers;
        return value instanceof List<?> list ? list.stream().mapToInt(v -> (Integer) v).toArray() : new int[0];
    }
    private static String[] strings(Object value) {
        return value instanceof List<?> list ? list.toArray(String[]::new) : new String[0];
    }
    private String name(String name) {
        if (name == null) return null;
        boolean local = name.startsWith(".");
        String mapped = mapper.mapType(JvmMetadataUtil.toJvmInternalName(name)).replace('$', '.');
        return local ? "." + mapped : mapped;
    }
    private void klass(KmClass klass) {
        String owner = JvmMetadataUtil.toJvmInternalName(klass.getName());
        klass.setName(name(klass.getName()));
        klass.getTypeParameters().forEach(this::parameter);
        klass.getSupertypes().forEach(this::type);
        klass.getContextReceiverTypes().forEach(this::type);
        type(klass.getInlineClassUnderlyingType());
        declarations(klass, owner);
        getLocalDelegatedProperties(klass).forEach(p -> property(p, owner));
        klass.getConstructors().forEach(c -> {
            c.getValueParameters().forEach(this::value);
            setSignature(c, method(getSignature(c), owner));
        });
        klass.getSealedSubclasses().replaceAll(this::name);
        String companion = klass.getCompanionObject();
        if (companion != null) klass.setCompanionObject(nested(owner, companion));
        klass.getNestedClasses().replaceAll(n -> nested(owner, n));
        String origin = getAnonymousObjectOriginName(klass);
        if (origin != null) setAnonymousObjectOriginName(klass, mapper.mapType(origin));
    }
    private String nested(String owner, String child) {
        String mapped = mapper.mapType(owner + "$" + child.replace('.', '$'));
        String parent = mapper.mapType(owner) + "$";
        return mapped.startsWith(parent) ? mapped.substring(parent.length()).replace('$', '.') : child;
    }
    private void pkg(KmPackage pkg) {
        declarations(pkg, null);
        getLocalDelegatedProperties(pkg).forEach(p -> property(p, null));
    }
    private void declarations(KmDeclarationContainer container, String owner) {
        container.getFunctions().forEach(f -> function(f, owner));
        container.getProperties().forEach(p -> property(p, owner));
        container.getTypeAliases().forEach(a -> {
            a.getTypeParameters().forEach(this::parameter); type(a.getUnderlyingType()); type(a.getExpandedType());
        });
    }
    private void type(KmType type) {
        if (type == null) return;
        if (type.getClassifier() instanceof KmClassifier.Class klass) type.setClassifier(new KmClassifier.Class(name(klass.getName())));
        else if (type.getClassifier() instanceof KmClassifier.TypeAlias alias) type.setClassifier(new KmClassifier.TypeAlias(name(alias.getName())));
        type.getArguments().forEach(p -> type(p.getType()));
        type(type.getAbbreviatedType()); type(type.getOuterType());
        if (type.getFlexibleTypeUpperBound() != null) type(type.getFlexibleTypeUpperBound().getType());
    }
    private void parameter(KmTypeParameter parameter) { parameter.getUpperBounds().forEach(this::type); }
    private void value(KmValueParameter value) { if (value != null) { type(value.getType()); type(value.getVarargElementType()); } }
    private void function(KmFunction function, String owner) {
        function.getTypeParameters().forEach(this::parameter);
        function.getContextReceiverTypes().forEach(this::type);
        function.getValueParameters().forEach(this::value); function.getContextParameters().forEach(this::value);
        type(function.getReceiverParameterType()); type(function.getReturnType());
        JvmMethodSignature signature = getSignature(function), mapped = method(signature, owner);
        if (signature != null && function.getName().equals(signature.getName())) function.setName(mapped.getName());
        setSignature(function, mapped);
        String origin = getLambdaClassOriginName(function);
        if (origin != null) setLambdaClassOriginName(function, mapper.mapType(origin));
    }
    private void property(KmProperty property, String owner) {
        property.getTypeParameters().forEach(this::parameter); property.getContextReceiverTypes().forEach(this::type);
        property.getContextParameters().forEach(this::value);
        type(property.getReceiverParameterType()); type(property.getReturnType()); value(property.getSetterParameter());
        JvmFieldSignature field = getFieldSignature(property);
        if (field != null) {
            String mapped = owner == null ? field.getName() : mapper.mapFieldName(owner, field.getName(), field.getDescriptor());
            setFieldSignature(property, new JvmFieldSignature(mapped, mapper.mapDesc(field.getDescriptor())));
        }
        setGetterSignature(property, method(getGetterSignature(property), owner));
        setSetterSignature(property, method(getSetterSignature(property), owner));
        setSyntheticMethodForAnnotations(property, method(getSyntheticMethodForAnnotations(property), owner));
        setSyntheticMethodForDelegate(property, method(getSyntheticMethodForDelegate(property), owner));
    }
    private JvmMethodSignature method(JvmMethodSignature signature, String owner) {
        return signature == null ? null : new JvmMethodSignature(owner == null ? signature.getName()
                : mapper.mapMethodName(owner, signature.getName(), signature.getDescriptor()), mapper.mapMethodDesc(signature.getDescriptor()));
    }
}
