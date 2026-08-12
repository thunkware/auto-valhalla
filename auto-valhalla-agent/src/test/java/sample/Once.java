package sample;

public class Once {
    private int a;
    private long b;

    public Once(int a, long b) {
        this.a = a;
        this.b = b;
    }

    public int a() {
        return a;
    }

    public long b() {
        return b;
    }
}
