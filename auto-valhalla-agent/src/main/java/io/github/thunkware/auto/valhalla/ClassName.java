package io.github.thunkware.auto.valhalla;

/**
 * A class name in both JVM (slash-separated) and Java (dot-separated) forms,
 * plus the JVM-form package name derived from the JVM name.
 *
 * <p>The JVM / instrumentation API delivers class names in internal form
 * ({@code com/example/Foo}); logging and file output use Java binary form
 * ({@code com.example.Foo}). Carrying both avoids repeated conversions.
 */
record ClassName(String jvm, String java) {

    /** Creates a ClassName from its JVM internal name ({@code com/example/Foo}). */
    static ClassName of(String jvm) {
        return new ClassName(jvm, jvm.replace('/', '.'));
    }

    /** JVM-form package name ({@code com/example} for {@code com/example/Foo};
     *  empty string for a default-package class). */
    String packageName() {
        return jvm.indexOf('/') < 0 ? "" : StringUtils.substringBeforeLast(jvm, "/");
    }

}
