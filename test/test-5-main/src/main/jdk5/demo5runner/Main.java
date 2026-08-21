package demo5runner;

import demo5.annotation.Point;
import io.github.thunkware.auto.valhalla.AutoValhallaAttachAgent;

/**
 * JDK 5 compatible entry point used to prove the agent is safe to load/attach
 * on a pre-Valhalla JVM. It exercises ordinary identity semantics; the agent
 * should print a warning and leave this class untouched. It also verifies that
 * the attach entry point ({@link AutoValhallaAttachAgent}) correctly reports
 * itself as unsupported on a JVM older than JDK 28.
 */
public final class Main {

    public static void main(String[] args) {
        // verify attach not supported
        try {
            AutoValhallaAttachAgent.attach();
            throw new AssertionError("FAIL: attach unexpectedly reported supported on Java "
                    + System.getProperty("java.version"));
        } catch (IllegalStateException expected) {
            System.out.println("OK: attach not supported on Java "
                    + System.getProperty("java.version") + ": " + expected.getMessage());
        }

        System.out.println("AutoValhalla demo-runner5 running on: "
                + System.getProperty("java.version"));
        Point p = new Point(1, 2);
        Point q = new Point(1, 2);
        System.out.println("p = " + p);
        System.out.println("p == q (identity expected on JDK 5): " + (p == q));
        System.out.println("OK: application executed without agent interference.");
    }
}