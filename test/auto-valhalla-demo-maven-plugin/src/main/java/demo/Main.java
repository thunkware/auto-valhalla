package demo;

import java.lang.reflect.Method;

public final class Main {

    public static void main(String[] args) throws Exception {
        int feature = jdkFeature();
        if (feature >= 28) {
            // compiled for JDK 17, so Class.isValue() is reached reflectively
            Method isValue = Class.class.getMethod("isValue");
            requireValue(isValue, "demo.Point");
            requireValue(isValue, "demo.Plain");
            System.out.println("OK: all classes are value classes");
        } else {
            System.out.println("running on Java " + feature
                    + "; value-class check skipped (requires JDK 28+)");
        }
        Point p = new Point(3, 4);
        System.out.println("sum=" + (p.x + p.y));
    }

    /** Forcefully asserts that {@code className} is a value class: any failure
     *  aborts the run with an exception and a non-zero exit code. */
    private static void requireValue(Method isValue, String className) throws Exception {
        Class<?> c = Class.forName(className);
        boolean value = (Boolean) isValue.invoke(c);
        System.out.println(className + ".isValue()=" + value);
        if (!value) {
            throw new IllegalStateException(className + " is NOT a value class; "
                    + "the multi-release variant was not used");
        }
    }

    private static int jdkFeature() {
        String spec = System.getProperty("java.specification.version", "1.8");
        if (spec.startsWith("1.")) {
            spec = spec.substring(2);
        }
        try {
            return Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}