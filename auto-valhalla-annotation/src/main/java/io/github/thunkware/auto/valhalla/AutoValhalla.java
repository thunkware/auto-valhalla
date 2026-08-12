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
 * <p>The class must satisfy the requirements of a value class: it must be
 * effectively immutable (all instance fields can be made {@code final}), extend
 * {@code java.lang.Object} directly, and every constructor must initialize all
 * instance fields before invoking {@code super()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AutoValhalla {
}
