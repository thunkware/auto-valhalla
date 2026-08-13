package demo.runner;

import java.util.Objects;

import demo5.annotation.Point;
import demo5.includes.Circle;
import demo5.includes.Square;
import demo16.annotation.Money;
import demo16.includes.Hsl;
import demo16.includes.Pair;

/**
 * Exercises the cross-version demos and verifies each class became a value
 * class or stayed an identity class according to the
 * {@code -Dauto-valhalla.expect} system property ({@code value} or
 * {@code identity}). Exits non-zero when any class behaves like the wrong
 * object form, so a demo class that silently fails to transform fails
 * run-demo.sh / build.sh instead of passing unnoticed.
 *
 * <p>Uses JDK 28 preview APIs ({@link Objects#hasIdentity}).
 */
public class Main {

    static void main(String[] args) {
        boolean expectValue = "value".equals(System.getProperty("auto-valhalla.expect", "identity"));
        System.out.println("=== auto-valhalla cross-version demo ===");
        System.out.println("expecting " + (expectValue ? "value" : "identity") + " classes");

        int failed = 0;

        Point p1 = new Point(5, 7);
        Point p2 = new Point(5, 7);
        System.out.println("Point  (demo5.annotation, @AutoValhalla, Java 5 bytecode)");
        System.out.println("  " + p1);
        System.out.println("  hasIdentity(p1):  " + Objects.hasIdentity(p1));
        System.out.println("  p1 == p2:         " + (p1 == p2));
        failed += check("Point", expectValue, p1, p2);

        Money m1 = new Money(1234, "USD");
        Money m2 = new Money(1234, "USD");
        System.out.println("Money  (demo16.annotation, @AutoValhalla, Java 16 bytecode)");
        System.out.println("  " + m1);
        System.out.println("  hasIdentity(m1):  " + Objects.hasIdentity(m1));
        System.out.println("  m1 == m2:         " + (m1 == m2));
        failed += check("Money", expectValue, m1, m2);

        Hsl h1 = new Hsl(120, 50, 50);
        Hsl h2 = new Hsl(120, 50, 50);
        System.out.println("Hsl    (demo16.includes, prefix selection, Java 16 bytecode)");
        System.out.println("  " + h1);
        System.out.println("  hasIdentity(h1):  " + Objects.hasIdentity(h1));
        System.out.println("  h1 == h2:         " + (h1 == h2));
        failed += check("Hsl", expectValue, h1, h2);

        Pair<Integer> r1 = new Pair<>(1, 2);
        Pair<Integer> r2 = new Pair<>(1, 2);
        System.out.println("Pair   (demo16.includes, plain record, rewritten by the agent)");
        System.out.println("  " + r1);
        System.out.println("  hasIdentity(r1):  " + Objects.hasIdentity(r1));
        System.out.println("  r1 == r2:         " + (r1 == r2));
        failed += check("Pair", expectValue, r1, r2);

        Square s1 = new Square(4);
        Square s2 = new Square(4);
        System.out.println("Square (demo5.includes, includes-mode=yolo, Java 5 bytecode)");
        System.out.println("  " + s1);
        System.out.println("  hasIdentity(s1):  " + Objects.hasIdentity(s1));
        System.out.println("  s1 == s2:         " + (s1 == s2));
        failed += check("Square", expectValue, s1, s2);

        Circle c1 = new Circle(5);
        Circle c2 = new Circle(5);
        System.out.println("Circle (demo5.includes, includes-mode=yolo, Java 5 bytecode)");
        System.out.println("  " + c1);
        System.out.println("  hasIdentity(c1):  " + Objects.hasIdentity(c1));
        System.out.println("  c1 == c2:         " + (c1 == c2));
        failed += check("Circle", expectValue, c1, c2);

        if (failed > 0) {
            System.out.println("FAILED: " + failed + " class(es) behave like the wrong object form");
            System.exit(1);
        }
        System.out.println("OK: all demo classes behave as "
                + (expectValue ? "value" : "identity") + " classes");
    }

    /**
     * Asserts {@code a}'s class matches the expected object form: a value class
     * must report no identity ({@code hasIdentity() == false}) and compare by
     * content ({@code a == b}); an identity class must keep its identity.
     *
     * @return the number of failed checks (0 or 1), summed by {@link #main}
     */
    private static int check(String name, boolean expectValue, Object a, Object b) {
        int failed = 0;
        if (Objects.hasIdentity(a) == expectValue) {
            System.out.println("  !! " + name + " should be a "
                    + (expectValue ? "value" : "identity") + " class, hasIdentity="
                    + Objects.hasIdentity(a));
            failed++;
        }
        if ((a == b) != expectValue) {
            System.out.println("  !! " + name + " == should be " + expectValue
                    + ", was " + (a == b));
            failed++;
        }
        return failed;
    }
}
