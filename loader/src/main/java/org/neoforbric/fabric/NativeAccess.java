package org.neoforbric.fabric;

import java.lang.reflect.*;
import org.neoforbric.loader.Failure;

/** Version-pinned passive access; never invokes FabricLoaderImpl.load/freeze or a native launcher. */
final class NativeAccess {
    private NativeAccess() {}
    static Object call(Object receiver, Class<?> type, String name, Class<?>[] signature, Object... args) {
        try {
            Method method = type.getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(receiver, args);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error instanceof InvocationTargetException wrapped ? wrapped.getCause() : error;
            Failure.rethrowFatal(cause); throw new Failure("FABRIC_RUNTIME_ABI", type.getName() + "." + name, cause);
        }
    }
    static void set(Object receiver, Class<?> type, String name, Object value) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(receiver, value); }
        catch (ReflectiveOperationException error) { throw new Failure("FABRIC_RUNTIME_ABI", type.getName() + "." + name, error); }
    }
    static Object construct(String name, Class<?>[] signature, Object... args) {
        try { Constructor<?> constructor = Class.forName(name).getDeclaredConstructor(signature); constructor.setAccessible(true); return constructor.newInstance(args); }
        catch (ReflectiveOperationException error) { throw new Failure("FABRIC_RUNTIME_ABI", name, error); }
    }
}
