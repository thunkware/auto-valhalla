package demo;

import io.github.thunkware.auto.valhalla.api.AutoValhalla;
import org.apiguardian.api.API;
import org.junit.jupiter.api.Disabled;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

@AutoValhalla
@API(status = EXPERIMENTAL) // verify test classpath
@Disabled // verify test classpath
public final class AnotherTestPoint {

    static {
        // verify test classpath
        Main.class.getName();
        PluginOutputTest.class.getName();
    }

    public final int x;
    public final int y;

    public AnotherTestPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

}
