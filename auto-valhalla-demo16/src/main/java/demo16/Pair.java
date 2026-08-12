package demo16;

/**
 * A plain record. Records are not made value classes by the JVM in this JDK, so
 * the agent rewrites them into value classes when they are selected (e.g. via an
 * {@code @AutoValhalla} annotation or an {@code includes} pattern). Included to
 * show that records are handled by the agent, not the JVM.
 */
public record Pair<T>(T first, T second) {
}
