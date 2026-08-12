package sample;

public class SampleX {
    private final long id;
    private final double rate;

    public SampleX(long id, double rate) {
        this.id = id;
        this.rate = rate;
    }

    public long id() {
        return id;
    }

    public double rate() {
        return rate;
    }
}
