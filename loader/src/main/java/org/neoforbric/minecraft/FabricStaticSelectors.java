package org.neoforbric.minecraft;

import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Static selectors and explicit remap=false selectors name runtime symbols directly. */
final class FabricStaticSelectors {
    static byte[] remap(byte[] bytes, FabricRefmaps.SelectorMapper mapper) {
        return remap(bytes, mapper, true);
    }
    static byte[] remap(byte[] bytes, FabricRefmaps.SelectorMapper mapper, boolean staticMixin) {
        ClassNode header = new ClassNode(); new ClassReader(bytes).accept(header, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        AnnotationNode mixin = mixin(header.visibleAnnotations);
        if (mixin == null) mixin = mixin(header.invisibleAnnotations);
        if (mixin == null) return bytes;
        boolean remap = remapEnabled(mixin, true);
        ClassNode type = new ClassNode(); new ClassReader(bytes).accept(type, 0);
        annotations(type.visibleAnnotations, mapper, staticMixin, true); annotations(type.invisibleAnnotations, mapper, staticMixin, true);
        for (MethodNode method : type.methods) {
            annotations(method.visibleAnnotations, mapper, staticMixin, remap); annotations(method.invisibleAnnotations, mapper, staticMixin, remap);
        }
        for (FieldNode field : type.fields) {
            annotations(field.visibleAnnotations, mapper, staticMixin, remap); annotations(field.invisibleAnnotations, mapper, staticMixin, remap);
        }
        ClassWriter output = new ClassWriter(0); type.accept(output); return output.toByteArray();
    }
    private static AnnotationNode mixin(List<AnnotationNode> annotations) {
        if (annotations != null) for (var annotation : annotations)
            if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return annotation;
        return null;
    }
    private static boolean remapEnabled(AnnotationNode annotation, boolean inherited) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals("remap")) return (Boolean) annotation.values.get(i + 1);
        return inherited;
    }
    private static void annotations(List<AnnotationNode> annotations, FabricRefmaps.SelectorMapper mapper, boolean staticMixin, boolean inheritedRemap) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations)
            if (annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/") || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/"))
                annotation(annotation, mapper, staticMixin, inheritedRemap);
    }
    private static void annotation(AnnotationNode annotation, FabricRefmaps.SelectorMapper mapper, boolean staticMixin, boolean inheritedRemap) {
        if (annotation.values == null) return;
        boolean remap = remapEnabled(annotation, inheritedRemap);
        for (int i = 1; i < annotation.values.size(); i += 2)
            annotation.values.set(i, value(annotation.values.get(i), mapper, staticMixin, remap));
    }
    @SuppressWarnings("unchecked")
    private static Object value(Object value, FabricRefmaps.SelectorMapper mapper, boolean staticMixin, boolean remap) {
        if (value instanceof String string) return staticMixin || !remap ? mapper.map(string) : string;
        if (value instanceof AnnotationNode nested) annotation(nested, mapper, staticMixin, remap);
        if (value instanceof List<?> list) {
            List<Object> values = (List<Object>) list;
            for (int i = 0; i < values.size(); i++) values.set(i, value(values.get(i), mapper, staticMixin, remap));
        }
        return value;
    }
}
