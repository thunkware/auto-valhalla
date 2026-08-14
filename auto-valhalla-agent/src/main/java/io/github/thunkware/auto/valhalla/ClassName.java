package io.github.thunkware.auto.valhalla;

/**
 * A class name in both JVM (slash-separated) and Java (dot-separated) forms.
 *
 * <p>The JVM / instrumentation API delivers class names in internal form
 * ({@code com/example/Foo}); logging and file output use Java binary form
 * ({@code com.example.Foo}). Carrying both avoids repeated conversions.
 */
record ClassName(String jvm, String java) {

    /** Creates a ClassName from its JVM jvm name ({@code com/example/Foo}). */
    static ClassName of(String jvm) {
        return new ClassName(jvm, jvm.replace('/', '.'));
    }

}
