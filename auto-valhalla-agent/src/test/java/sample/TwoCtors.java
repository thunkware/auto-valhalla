package sample;

public class TwoCtors {
    private int a;

    public TwoCtors() {
        this.a = 0;
    }

    public TwoCtors(int a) {
        this.a = a;
    }

    public int a() {
        return a;
    }
}
