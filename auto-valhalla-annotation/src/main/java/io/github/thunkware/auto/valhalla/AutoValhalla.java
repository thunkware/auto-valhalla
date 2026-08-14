package io.github.thunkware.auto.valhalla;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class to be automatically transformed into a value class (as defined
 * by <a href="https://openjdk.org/jeps/401">JEP 401</a>) at load time by the
 * {@code auto-valhalla} agent.
 *
 * <p>By default ({@code annotation-mode=safe}), the class must satisfy all of
 * the following conditions:
 * <ul>
 *   <li>Is {@code final} (abstract classes are not yet supported).</li>
 *   <li>Extends only {@code java.lang.Object} or {@code java.lang.Record}
 *       — not another class.</li>
 *   <li>Has at least one instance field.</li>
 *   <li>All non-{@code static}, non-{@code final} instance fields are
 *       {@code private}.</li>
 *   <li>Has no {@code synchronized} instance methods.</li>
 *   <li>Has no {@code synchronized} blocks ({@code monitorenter} bytecode) in
 *       any of its methods (more conservative than checking for
 *       {@code synchronized(this)} specifically — any locked object causes
 *       rejection).</li>
 * </ul>
 *
 * <p>If any condition is not met, transformation fails according to
 * {@code annotation.on-fail} (default: throw a {@code LinkageError}).
 * Other modes — such as {@code mark-class-final} or {@code ignore-synchronized}
 * — relax individual conditions; see the agent documentation for details.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AutoValhalla {
}
