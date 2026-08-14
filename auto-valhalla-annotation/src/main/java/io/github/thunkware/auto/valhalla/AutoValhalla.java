package io.github.thunkware.auto.valhalla;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class to be automatically transformed into a JEP 401 value class at
 * load time by the {@code auto-valhalla} agent.
 *
 * <p>By default ({@code annotation-mode=safe}), the class must satisfy all of
 * the following conditions:
 * <ul>
 *   <li>Is {@code final} (not abstract, not an interface, enum, or annotation).</li>
 *   <li>Extends {@code java.lang.Object} or {@code java.lang.Record} directly
 *       — not another identity class.</li>
 *   <li>Has at least one instance field.</li>
 *   <li>Has no {@code synchronized} instance methods.</li>
 *   <li>Has no {@code synchronized} blocks ({@code monitorenter} bytecode) in
 *       any method.</li>
 *   <li>All non-static, non-{@code final} instance fields are {@code private}
 *       (so they can be safely marked {@code final} by the agent without
 *       breaking sibling writers).</li>
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
