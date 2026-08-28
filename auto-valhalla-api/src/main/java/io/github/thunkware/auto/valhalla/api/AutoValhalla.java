package io.github.thunkware.auto.valhalla.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class to be automatically transformed into a <i>value class</i>
 * <ul>
 *     <li>at build time by auto-valhalla-maven-plugin, or</li>
 *     <li>at load time by auto-valhalla javaagent</li>
 *  </ul>
 *
 * <br/>
 * <br/>
 * Example usage:
 * <pre>
 * &#64AutoValhalla
 * public final class Point {
 *     public final int x;           // final instance fields
 *     public final int y;
 *     public Point(int x, int y) {
 *         this.x = x;
 *         this.y = y;
 *     }
 * }
 *
 * &#64AutoValhalla
 * public record Currency(String code) { }  // or a record class
 * </pre>
 *
 * <b>For build time transformation</b>, see auto-valhalla-maven-plugin documentation. The remainder of javadoc
 * focuses on auto-valhalla javaagent.
 *
 * <p>In javaagent's default ({@code annotation-mode=safe}), the class must satisfy all of
 * the following conditions:
 * <ul>
 *   <li>Is {@code final} (abstract classes are not yet supported).</li>
 *   <li>Extends only {@code java.lang.Object} or {@code java.lang.Record}
 *       — not another class.</li>
 *   <li>Has at least one instance field.</li>
 *   <li>All instance fields are {@code final}.</li>
 *   <li>Has no {@code synchronized} instance methods.</li>
 *   <li>Has no {@code synchronized} blocks ({@code monitorenter} bytecode) in
 *       any of its methods (more conservative than checking for
 *       {@code synchronized(this)} specifically — any locked object causes
 *       rejection).</li>
 * </ul>
 *
 * <p>If any condition is not met, transformation fails according to
 * {@code annotation.on-fail} (default: throw a {@code LinkageError}).
 * Other modes — such as {@code mark-class-final} or {@code remove-synchronized}
 * — relax individual conditions; see the agent documentation for details.
 *
 * <p>Use {@link AutoValhallaVerifier} to check these conditions at build
 * time, before running the agent:
 * <pre>
 * AutoValhallaVerifier.verify(Foo.class, Bar.class);
 * </pre>
 *
 * See <a href="https://openjdk.org/jeps/401">JEP-401</a> of Project Valhalla for value class details.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AutoValhalla {
}
