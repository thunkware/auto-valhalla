package io.github.thunkware.auto.valhalla;

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

/** Factory for {@link ClassFile} instances configured with an application-aware
 *  class-hierarchy resolver so stack-map generation succeeds for classes whose
 *  referenced types are not on the bootstrap classpath. */
public final class ClassFiles {

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
     * @param loader the classloader used to look up {@code .class} resources;
     *               {@code null} is treated as the system classloader
     */
    public static ClassFile of(ClassLoader loader) {
        ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
        ClassHierarchyResolver appResolver = desc -> {
            // ClassDesc.descriptorString() returns "Lcom/example/Foo;" — strip L and ;
            String d = desc.descriptorString();
            if (d.length() < 3 || d.charAt(0) != 'L') {
                return null; // primitive or array — no .class resource
            }
            String resource = d.substring(1, d.length() - 1) + ".class";
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
                .orElse(desc -> ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object));
        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
    }
}
