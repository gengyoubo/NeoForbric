package org.neoforbric.minecraft;

import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Loom static selectors already name intermediary symbols, even inside remap=false mixins. */
final class FabricStaticSelectors {
    static byte[] remap(byte[] bytes, FabricRefmaps.SelectorMapper mapper) {
        ClassNode type = new ClassNode(); new ClassReader(bytes).accept(type, 0);
        annotations(type.visibleAnnotations, mapper); annotations(type.invisibleAnnotations, mapper);
        for (MethodNode method : type.methods) {
            annotations(method.visibleAnnotations, mapper); annotations(method.invisibleAnnotations, mapper);
        }
        for (FieldNode field : type.fields) {
            annotations(field.visibleAnnotations, mapper); annotations(field.invisibleAnnotations, mapper);
        }
        ClassWriter output = new ClassWriter(0); type.accept(output); return output.toByteArray();
    }
    private static void annotations(List<AnnotationNode> annotations, FabricRefmaps.SelectorMapper mapper) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations)
            if (annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/") || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/"))
                annotation(annotation, mapper);
    }
    private static void annotation(AnnotationNode annotation, FabricRefmaps.SelectorMapper mapper) {
        if (annotation.values == null) return;
        for (int i = 1; i < annotation.values.size(); i += 2)
            annotation.values.set(i, value(annotation.values.get(i), mapper));
    }
    @SuppressWarnings("unchecked")
    private static Object value(Object value, FabricRefmaps.SelectorMapper mapper) {
        if (value instanceof String string) return mapper.map(string);
        if (value instanceof AnnotationNode nested) annotation(nested, mapper);
        if (value instanceof List<?> list) {
            List<Object> values = (List<Object>) list;
            for (int i = 0; i < values.size(); i++) values.set(i, value(values.get(i), mapper));
        }
        return value;
    }
}
