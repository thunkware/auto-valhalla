package sample;

/**
 * A package-private class with member classes, mirroring the shape of
 * Spring Boot's {@code org.springframework.boot.loader.zip.FileDataBlock}
 * (a non-final class with static member classes and a static-ref inner
 * class). Its access flags and InnerClasses attribute are what exposed the
 * value-class rewriter bug: bumping the class-file version to the inline
 * (value-class) version without updating the member-class access-flags made
 * the JVM throw "Illegal class modifiers in inner class ... of class ...".
 */
final class NestedOuter {
    static class Inner {
        private final long field;

        Inner(long field) {
            this.field = field;
        }
    }

    static final class Holder {
        private static final Inner INNER = new Inner(7);

        private Holder() {
        }
    }

    private NestedOuter() {
    }
}
