package demo5runner;

import demo5.annotation.Point;

/**
 * JDK 5 compatible entry point used to prove the agent is safe to load/attach
 * on a pre-Valhalla JVM. It only exercises ordinary identity semantics; the
 * agent should print a warning and leave this class untouched.
 */
public final class Main {

    public static void main(String[] args) {
        System.out.println("AutoValhalla demo-runner5 running on: "
                + System.getProperty("java.version"));
        Point p = new Point(1, 2);
        Point q = new Point(1, 2);
        System.out.println("p = " + p);
        System.out.println("p == q (identity expected on JDK 5): " + (p == q));
        System.out.println("OK: application executed without agent interference.");
    }
}
