package demo;

import io.github.thunkware.auto.valhalla.api.AutoValhalla;

@AutoValhalla
public class Point {

    public final int x;
    public final int y;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }
}
