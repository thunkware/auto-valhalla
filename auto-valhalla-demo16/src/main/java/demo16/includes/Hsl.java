package demo16.includes;

/**
 * Not annotated. Converted to a value class only because the agent is started
 * with a package prefix that matches {@code demo16.includes} (e.g.
 * {@code -Dauto-valhalla.includes=demo16.includes.}). Demonstrates that
 * selection by package prefix works for classes compiled to Java 16 bytecode.
 */
public class Hsl {
    private final int h;
    private final int s;
    private final int l;

    public Hsl(int h, int s, int l) {
        this.h = h;
        this.s = s;
        this.l = l;
    }

    public int h() { return h; }
    public int s() { return s; }
    public int l() { return l; }

    @Override
    public String toString() {
        return "Hsl(" + h + ", " + s + ", " + l + ")";
    }
}
