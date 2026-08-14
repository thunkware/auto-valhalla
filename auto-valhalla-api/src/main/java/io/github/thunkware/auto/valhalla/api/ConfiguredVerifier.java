package io.github.thunkware.auto.valhalla.api;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * A verifier configured for a specific combination of auto-valhalla modes.
 * Obtain an instance via {@link AutoValhallaVerifier#safe()} and optionally
 * chain builder methods before calling {@link #verify} or {@link #violations}.
 *
 * <p>Uses only {@link java.lang.reflect} APIs available since JDK 1.1.
 * Cannot inspect {@code monitorenter} (synchronized blocks) — only the
 * method-level {@code synchronized} modifier is visible via reflection.
 * The agent rejects synchronized blocks regardless of {@code remove-synchronized};
 * avoid them in classes you intend to convert.
 */
public final class ConfiguredVerifier {

    private final boolean markClassFinal;
    private final boolean removeSynchronized;
    private final boolean markFieldsFinal;

    ConfiguredVerifier(boolean markClassFinal, boolean removeSynchronized, boolean markFieldsFinal) {
        this.markClassFinal = markClassFinal;
        this.removeSynchronized = removeSynchronized;
        this.markFieldsFinal = markFieldsFinal;
    }

    /** Returns a new verifier with {@code mark-class-final} enabled. */
    public ConfiguredVerifier markClassFinal() {
        return new ConfiguredVerifier(true, this.removeSynchronized, this.markFieldsFinal);
    }

    /** Returns a new verifier with {@code remove-synchronized} enabled. */
    public ConfiguredVerifier removeSynchronized() {
        return new ConfiguredVerifier(this.markClassFinal, true, this.markFieldsFinal);
    }

    /** Returns a new verifier with {@code mark-fields-final} enabled. */
    public ConfiguredVerifier markFieldsFinal() {
        return new ConfiguredVerifier(this.markClassFinal, this.removeSynchronized, true);
    }

    /**
     * Returns a list of {@code "ClassName: reason"} strings for every structural
     * condition not met by the given classes, or an empty list if all pass.
     */
    public List<String> violations(Class<?>... classes) {
        List<String> all = new ArrayList<String>();
        for (Class<?> c : classes) {
            for (String problem : check(c)) {
                all.add(c.getName() + ": " + problem);
            }
        }
        return all;
    }

    /**
     * Checks the given classes and throws {@link IllegalArgumentException} listing
     * all violations if any class is unlikely to work with the auto-valhalla agent.
     *
     * @throws IllegalArgumentException if one or more classes fail the structural checks
     */
    public void verify(Class<?>... classes) {
        List<String> v = violations(classes);
        if (v.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("AutoValhalla verification failed:");
        for (String s : v) {
            sb.append("\n  ").append(s);
        }
        throw new IllegalArgumentException(sb.toString());
    }

    private List<String> check(Class<?> c) {
        List<String> problems = new ArrayList<String>();

        if (c.isInterface()) {
            problems.add("is an interface; a value class cannot be an interface");
            return problems;
        }
        if (c.isEnum()) {
            problems.add("is an enum; a value class cannot be an enum");
            return problems;
        }
        if (c.isAnnotation()) {
            problems.add("is an annotation type; a value class cannot be an annotation type");
            return problems;
        }

        int mods = c.getModifiers();

        if (Modifier.isAbstract(mods)) {
            problems.add("is abstract; converting an abstract class is not yet supported");
        }

        if (!markClassFinal && !Modifier.isFinal(mods) && !isRecord(c)) {
            problems.add("is not final; add 'final' or enable markClassFinal() mode");
        }

        Class<?> sup = c.getSuperclass();
        if (sup != null && sup != Object.class && !isRecord(c)) {
            problems.add("extends " + sup.getName()
                    + "; a value class may only extend Object or Record");
        }

        Field[] fields = c.getDeclaredFields();
        boolean hasInstanceField = false;
        List<String> openFields = new ArrayList<String>();
        for (Field f : fields) {
            int fmods = f.getModifiers();
            if (!Modifier.isStatic(fmods)) {
                hasInstanceField = true;
                if (!Modifier.isFinal(fmods) && !Modifier.isPrivate(fmods)) {
                    openFields.add(f.getName());
                }
            }
        }
        if (!hasInstanceField) {
            problems.add("has no instance fields; a value class should have instance state");
        }
        if (!openFields.isEmpty()) {
            problems.add("has non-private mutable instance field(s) " + openFields
                    + "; another class may write them, preventing conversion");
        }

        if (!removeSynchronized) {
            Method[] methods = c.getDeclaredMethods();
            List<String> syncMethods = new ArrayList<String>();
            for (Method m : methods) {
                int mmods = m.getModifiers();
                if (!Modifier.isStatic(mmods) && Modifier.isSynchronized(mmods)) {
                    syncMethods.add(m.getName());
                }
            }
            if (!syncMethods.isEmpty()) {
                problems.add("has synchronized instance method(s) " + syncMethods
                        + "; remove the modifier or enable removeSynchronized() mode");
            }
        }

        return problems;
    }

    private static boolean isRecord(Class<?> c) {
        Class<?> sup = c.getSuperclass();
        return sup != null && "java.lang.Record".equals(sup.getName());
    }
}
