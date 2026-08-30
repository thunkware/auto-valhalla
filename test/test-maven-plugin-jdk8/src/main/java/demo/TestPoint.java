package demo;

import io.github.thunkware.auto.valhalla.api.AutoValhalla;
import org.apiguardian.api.API;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

@AutoValhalla
@API(status = EXPERIMENTAL) // verify compile-time classpath
public final class TestPoint {

    static {
        // verify compile-time classpath
        Main.class.getName();
    }

    public final int x;
    public final int y;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }
}
