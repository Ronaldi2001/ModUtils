import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.classfile.constantpool.*;
import java.lang.invoke.MethodType;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.lang.reflect.*;

public class ApiCheck {
    static URLClassLoader cl;
    static Map<String, Boolean> cache = new HashMap<>();
    public static void main(String[] a) throws Exception {
        // a[0]=server jar, a[1]=libraries dir, rest = plugin jars
        List<URL> urls = new ArrayList<>();
        urls.add(Path.of(a[0]).toUri().toURL());
        try (var s = Files.list(Path.of(a[1]))) { for (Path p : (Iterable<Path>) s::iterator) if (p.toString().endsWith(".jar")) urls.add(p.toUri().toURL()); }
        cl = new URLClassLoader(urls.toArray(new URL[0]), null);
        TreeMap<String, TreeSet<String>> missing = new TreeMap<>();
        for (int i = 2; i < a.length; i++) {
            try (JarFile jf = new JarFile(a[i])) {
                for (var e : Collections.list(jf.entries())) {
                    if (!e.getName().endsWith(".class")) continue;
                    ClassModel cm = ClassFile.of().parse(jf.getInputStream(e).readAllBytes());
                    String user = cm.thisClass().asInternalName();
                    for (var el : cm) {
                        if (!(el instanceof MethodModel mm)) continue;
                        for (var me : mm) {
                            if (!(me instanceof CodeModel code)) continue;
                            for (var ce : code) {
                                if (ce instanceof FieldInstruction fi) {
                                    String owner = fi.owner().asInternalName();
                                    if (owner.startsWith("net/minecraft/") || owner.startsWith("org/bukkit/")) {
                                        String key = owner + "#" + fi.name().stringValue();
                                        if (!fieldOk(owner, fi.name().stringValue()))
                                            missing.computeIfAbsent(key, k -> new TreeSet<>()).add(user.substring(user.lastIndexOf('/') + 1));
                                    }
                                }
                                if (ce instanceof InvokeInstruction in) {
                                    String owner = in.owner().asInternalName();
                                    if (!(owner.startsWith("org/bukkit/") && !owner.startsWith("org/bukkit/craftbukkit")) && !owner.startsWith("io/papermc/") && !owner.startsWith("com/destroystokyo/") && !owner.startsWith("net/minecraft/")) continue;
                                    String key = owner + "." + in.name().stringValue() + in.type().stringValue();
                                    if (!ok(owner, in.name().stringValue(), in.type().stringValue()))
                                        missing.computeIfAbsent(key, k -> new TreeSet<>()).add(user.substring(user.lastIndexOf('/') + 1));
                                }
                            }
                        }
                    }
                }
            }
        }
        missing.forEach((k, v) -> System.out.println(k + "  <- " + v));
        System.out.println("missing: " + missing.size());
    }
    static boolean ok(String owner, String name, String desc) {
        String key = owner + "." + name + desc;
        return cache.computeIfAbsent(key, k -> {
            try {
                Class<?> c = Class.forName(owner.replace('/', '.'), false, cl);
                return has(c, name, desc);
            } catch (Throwable t) { return false; }
        });
    }
    static boolean fieldOk(String owner, String name) {
        try {
            Class<?> c = Class.forName(owner.replace('/', '.'), false, cl);
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) if (f.getName().equals(name)) return true;
                for (Class<?> i : k.getInterfaces()) if (fieldOk(i.getName().replace('.', '/'), name)) return true;
            }
            return false;
        } catch (Throwable t) { return false; }
    }
    static boolean has(Class<?> c, String name, String desc) {
        if (c == null) return false;
        if (name.equals("<init>")) {
            try { for (Constructor<?> k : c.getDeclaredConstructors())
                if (MethodType.methodType(void.class, k.getParameterTypes()).toMethodDescriptorString().equals(desc)) return true; } catch (Throwable t) { return true; }
            return false;
        }
        try {
            for (Method m : c.getDeclaredMethods())
                if (m.getName().equals(name) && MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString().equals(desc)) return true;
        } catch (Throwable t) { return true; }
        if (has(c.getSuperclass(), name, desc)) return true;
        for (Class<?> i : c.getInterfaces()) if (has(i, name, desc)) return true;
        if (c.isInterface() || true) { try { if (Object.class.getMethods().length > 0) for (Method m : Object.class.getMethods()) if (m.getName().equals(name) && MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString().equals(desc)) return true; } catch (Throwable t) {} }
        return false;
    }
}
