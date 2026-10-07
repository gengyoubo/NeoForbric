package org.neoforbric.neoforge;

import java.net.*;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

/** The upstream AT engine runs as a passive tool sharing the kernel's ASM types. */
public final class NeoForgeAccessTransformers implements TransformPipeline.Transformer, AutoCloseable {
    private final URLClassLoader tools;
    private final Object engine;
    private final Method transform;
    private final Set<Type> targets;
    @SuppressWarnings("unchecked")
    public NeoForgeAccessTransformers(List<Path> dependencies, List<Path> rules) throws Exception {
        URL[] urls = new URL[dependencies.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = dependencies.get(i).toUri().toURL();
        tools = new URLClassLoader("NeoForbric-NeoForge-Tools", urls, NeoForgeAccessTransformers.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("org.antlr.v4.runtime.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name); if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type); return type;
                }
            }
        };
        Class<?> api = Class.forName("net.neoforged.accesstransformer.api.AccessTransformerEngine", true, tools);
        engine = api.getMethod("newEngine").invoke(null);
        for (Path rule : rules) api.getMethod("loadATFromPath", Path.class).invoke(engine, rule);
        targets = Set.copyOf((Set<Type>)api.getMethod("getTargets").invoke(engine));
        transform = api.getMethod("transform", ClassNode.class, Type.class);
    }
    @Override public String id() { return "neoforge-access-transformers"; }
    @Override public byte[] transform(TransformPipeline.Context context, byte[] bytes) throws Exception {
        Type type = Type.getObjectType(context.name().replace('.', '/'));
        if (!targets.contains(type)) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        transform.invoke(engine, node, type);
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    @Override public void close() throws java.io.IOException { tools.close(); }
}
