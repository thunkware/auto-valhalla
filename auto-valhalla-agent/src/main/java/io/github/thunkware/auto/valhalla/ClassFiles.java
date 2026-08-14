package io.github.thunkware.auto.valhalla;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassHierarchyResolver.ClassHierarchyInfo;
import java.lang.constant.ConstantDescs;

/** Factory for {@link ClassFile} instances configured with an application-aware
 *  class-hierarchy resolver so stack-map generation succeeds for classes whose
 *  referenced types are not on the bootstrap classpath. */
final class ClassFiles {

    private ClassFiles() {}

    /**
     * Returns a {@link ClassFile} whose {@link ClassHierarchyResolver} uses the
     * system classloader. The system classloader knows JDK types but never holds
     * application classes loaded by a child classloader (e.g. Spring Boot's
     * {@code LaunchedClassLoader}). This prevents two problems:
     * <ul>
     *   <li>{@code IllegalArgumentException: Could not resolve class} — thrown
     *       when stack-map regeneration encounters types not on the bootstrap
     *       classpath;</li>
     *   <li>"duplicate class definition" errors — triggered when
     *       {@code ofClassLoading(loader)} calls {@code Class.forName} for the
     *       very class currently being defined by the same loader.</li>
     * </ul>
     * Any type absent from the system classloader falls back to {@code Object},
     * which is always a valid conservative approximation for stack-map merging.
     */
    static ClassFile of() {
        ClassHierarchyResolver resolver = ClassHierarchyResolver
                .ofClassLoading(ClassLoader.getSystemClassLoader())
                .orElse(desc -> ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object));
        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
    }
}
