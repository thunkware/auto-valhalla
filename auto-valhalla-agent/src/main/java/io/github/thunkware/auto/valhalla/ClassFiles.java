package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassHierarchyResolver.ClassHierarchyInfo;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Factory for {@link ClassFile} instances configured with an application-aware
 *  class-hierarchy resolver so stack-map generation succeeds for classes whose
 *  referenced types are not on the bootstrap classpath. */
public final class ClassFiles {

    /** One {@link ClassFile} per class loader: each carries a resolver cache, so
     *  repeating a transform for the same loader no longer re-reads and re-parses
     *  the {@code .class} resources of the same supertypes. Weak keys so a cached
     *  entry never keeps a loader alive; the bootstrap/system loader is the
     *  {@code null} key. */
    private static final Map<ClassLoader, ClassFile> CACHE = new WeakHashMap<>();

    private ClassFiles() {}

    /**
     * Returns a {@link ClassFile} whose {@link ClassHierarchyResolver} reads
     * class-file bytes from {@code loader} via {@link ClassLoader#getResourceAsStream}
     * rather than calling {@link Class#forName}. Reading bytes does not trigger
     * {@code defineClass}, so the resolver never causes a "duplicate class
     * definition" error even when it encounters the class currently being
     * transformed.
     *
     * <p>When a class-file resource cannot be found (e.g. dynamically generated
     * proxies or lambda forms that have no {@code .class} file), the resolver
     * returns {@code null} and the chain falls back to {@code Object}, which is
     * always a valid conservative approximation for stack-map merging.
     *
     * <p>Instances are cached per loader, together with the hierarchy information
     * they have resolved so far.
     *
     * @param loader the classloader used to look up {@code .class} resources;
     *               {@code null} is treated as the system classloader
     */
    public static ClassFile of(ClassLoader loader) {
        synchronized (CACHE) {
            ClassFile cached = CACHE.get(loader);
            if (cached == null) {
                cached = create(loader);
                CACHE.put(loader, cached);
            }
            return cached;
        }
    }

    private static ClassFile create(ClassLoader loader) {
        ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
        ClassHierarchyResolver appResolver = desc -> {
            // ClassDesc.descriptorString() returns "Lcom/example/Foo;" — strip L and ;
            String d = desc.descriptorString();
            if (d.length() < 3 || d.charAt(0) != 'L') {
                return null; // primitive or array — no .class resource
            }
            String resource = StringUtils.substringBetween(d, "L", ";") + ".class";
            try (InputStream is = cl.getResourceAsStream(resource)) {
                if (is == null) {
                    return null;
                }
                ClassModel cm = ClassFile.of().parse(is.readAllBytes());
                if (cm.flags().has(AccessFlag.INTERFACE)) {
                    return ClassHierarchyInfo.ofInterface();
                }
                ClassDesc superDesc = cm.superclass()
                        .map(ClassEntry::asSymbol)
                        .orElse(null);
                return ClassHierarchyInfo.ofClass(superDesc);
            } catch (IOException | IllegalArgumentException e) {
                return null;
            }
        };
        ClassHierarchyResolver resolver = appResolver
                .orElse(desc -> ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object))
                // Memoize: stack-map generation asks about the same supertypes over
                // and over, and every miss costs a resource read plus a parse.
                // ConcurrentHashMap because class loading is concurrent.
                .cached(ConcurrentHashMap::new);
        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
    }
}
