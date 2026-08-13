package demo.runner;

import java.util.Objects;

import demo5.Point;
import demo5.Square;
import demo16.Hsl;
import demo16.Money;
import demo16.Pair;

/**
 * Exercises the cross-version demos and reports whether each class became a
 * value class. Uses JDK 28 preview APIs ({@link Objects#hasIdentity}).
 */
public class Main {

    static void main(String[] args) {
        System.out.println("=== auto-valhalla cross-version demo ===");

        Point p1 = new Point(5, 7);
        Point p2 = new Point(5, 7);
        System.out.println("Point  (demo5, @AutoValhalla, Java 5 bytecode)");
        System.out.println("  " + p1);
        System.out.println("  hasIdentity(p1):  " + Objects.hasIdentity(p1));
        System.out.println("  p1 == p2:         " + (p1 == p2));

        Money m1 = new Money(1234, "USD");
        Money m2 = new Money(1234, "USD");
        System.out.println("Money  (demo16, @AutoValhalla, Java 16 bytecode)");
        System.out.println("  " + m1);
        System.out.println("  hasIdentity(m1):  " + Objects.hasIdentity(m1));
        System.out.println("  m1 == m2:         " + (m1 == m2));

        Hsl h1 = new Hsl(120, 50, 50);
        Hsl h2 = new Hsl(120, 50, 50);
        System.out.println("Hsl    (demo16, prefix selection, Java 16 bytecode)");
        System.out.println("  " + h1);
        System.out.println("  hasIdentity(h1):  " + Objects.hasIdentity(h1));
        System.out.println("  h1 == h2:         " + (h1 == h2));

        Pair<Integer> r1 = new Pair<>(1, 2);
        Pair<Integer> r2 = new Pair<>(1, 2);
        System.out.println("Pair   (demo16, plain record, rewritten by the agent)");
        System.out.println("  " + r1);
        System.out.println("  hasIdentity(r1):  " + Objects.hasIdentity(r1));
        System.out.println("  r1 == r2:         " + (r1 == r2));

        Square s1 = new Square(4);
        Square s2 = new Square(4);
        System.out.println("Square (demo5, includes-mode=yolo, Java 5 bytecode)");
        System.out.println("  " + s1);
        System.out.println("  hasIdentity(s1):  " + Objects.hasIdentity(s1));
        System.out.println("  s1 == s2:         " + (s1 == s2));
    }
}
