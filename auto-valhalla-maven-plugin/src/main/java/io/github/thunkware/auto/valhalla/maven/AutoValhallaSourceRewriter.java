package io.github.thunkware.auto.valhalla.maven;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Source-level counterpart of the agent's class selection. Instead of
 * rewriting bytecode, this class finds the top-level types in a Java source
 * file, tells which ones carry the {@code @AutoValhalla} annotation, and
 * produces an adapted copy in which selected {@code class}/{@code record}
 * declarations go through the {@code value class}/{@code value record}
 * transformation that the JDK 28 compiler implements natively.
 *
 * <p>Only {@code class} and {@code record} declarations can be turned into
 * value types; {@code interface}/{@code enum} declarations are left alone.
 * Comment and string content is never touched.
 */
public final class AutoValhallaSourceRewriter {

    /** Fully qualified annotation name, as written in source. */
    public static final String AUTO_VALHALLA_ANNOTATION =
            "io.github.thunkware.auto.valhalla.api.AutoValhalla";

    /** The simple annotation name, as written after an import. */
    public static final String AUTO_VALHALLA_SIMPLE = "AutoValhalla";

    private static final Set<String> TYPE_KEYWORDS;

    static {
        Set<String> keywords = new HashSet<>();
        keywords.add("class");
        keywords.add("record");
        keywords.add("interface");
        keywords.add("enum");
        TYPE_KEYWORDS = Collections.unmodifiableSet(keywords);
    }

    private AutoValhallaSourceRewriter() {
    }

    /** A top-level type declaration found by {@link #topLevelTypes}. */
    public static final class TypeDeclaration {

        /** The simple name of the type. */
        public final String name;

        /** The declaration keyword: {@code class}, {@code record},
         *  {@code interface}, or {@code enum}. */
        public final String kind;

        /** True when the {@code @AutoValhalla} annotation is attached to the
         *  declaration (either by simple name or fully qualified). */
        public final boolean annotated;

        /** Char offset of the {@code class}/{@code record} keyword token in the
         *  original source, used by {@link #adapt}. */
        final int keywordOffset;

        TypeDeclaration(String name, String kind, boolean annotated, int keywordOffset) {
            this.name = name;
            this.kind = kind;
            this.annotated = annotated;
            this.keywordOffset = keywordOffset;
        }
    }

    /**
     * Lexes {@code source}, ignoring comments and string/char literals, and
     * returns every top-level type declaration with its name, kind, whether the
     * {@code @AutoValhalla} annotation is attached, and the offset of its
     * declaration keyword.
     */
    public static List<TypeDeclaration> topLevelTypes(String source) {
        List<TypeDeclaration> declarations = new ArrayList<>();
        List<String> pendingAnnotations = new ArrayList<>();
        int depth = 0;
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i);
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i);
            } else if (c == '"' || c == '\'') {
                i = skipLiteral(source, i, c);
            } else if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    // a top-level type body closed: later annotations belong to
                    // the next declaration, not a lingering one
                    pendingAnnotations.clear();
                }
                i++;
            } else if (c == ';' && depth == 0) {
                pendingAnnotations.clear();
                i++;
            } else if (depth > 0) {
                i++;
            } else if (c == '@') {
                int j = readIdentifier(source, i + 1);
                pendingAnnotations.add(j == i + 1 ? "" : source.substring(i + 1, j));
                i = j;
            } else if (Character.isJavaIdentifierStart(c)) {
                int keywordStart = i;
                int j = readIdentifier(source, i);
                String token = source.substring(i, j);
                if (TYPE_KEYWORDS.contains(token)) {
                    int k = skipWhitespace(source, j);
                    int nameEnd = readIdentifier(source, k);
                    String name = nameEnd == k ? "" : source.substring(k, nameEnd);
                    declarations.add(new TypeDeclaration(
                            name, token, isValhalla(pendingAnnotations), keywordStart));
                    pendingAnnotations.clear();
                    i = j;
                } else {
                    // a modifier or other identifier at the top level
                    i = j;
                }
            } else {
                i++;
            }
        }
        return declarations;
    }

    private static boolean isValhalla(List<String> annotations) {
        for (String annotation : annotations) {
            if (AUTO_VALHALLA_SIMPLE.equals(annotation)
                    || AUTO_VALHALLA_ANNOTATION.equals(annotation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a copy of {@code source} in which every top-level
     * {@code class}/{@code record} whose name is in {@code selectedNames} has
     * been changed into a {@code value class}/{@code value record}. Everything
     * else, including comments and strings, is preserved verbatim.
     */
    public static String adapt(String source, Collection<String> selectedNames) {
        List<TypeDeclaration> declarations = topLevelTypes(source);
        List<Integer> offsets = new ArrayList<>();
        for (TypeDeclaration declaration : declarations) {
            if (!selectedNames.contains(declaration.name)) {
                continue;
            }
            if ("class".equals(declaration.kind) || "record".equals(declaration.kind)) {
                offsets.add(declaration.keywordOffset);
            }
        }
        if (offsets.isEmpty()) {
            return source;
        }
        offsets.sort((a, b) -> Integer.compare(b, a));
        StringBuilder result = new StringBuilder(source);
        for (int offset : offsets) {
            result.insert(offset, "value ");
        }
        return result.toString();
    }

    /**
     * Mirrors {@code ValueClassTransformer#patternMatches}: {@code *} matches
     * everything; a pattern ending in {@code /} is a package-prefix match;
     * otherwise the pattern matches an exact class or an exact or recursive
     * package. Patterns may use dots or slashes.
     */
    public static boolean patternMatches(Collection<String> patterns, String internal) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String pkg = internal.indexOf('/') < 0 ? "" : internal.substring(0, internal.lastIndexOf('/'));
        for (String p : patterns) {
            String norm = p.replace('.', '/');
            if (norm.equals("*")) {
                return true;
            }
            if (norm.endsWith("/")) {
                if (internal.startsWith(norm)) {
                    return true;
                }
            } else if (internal.equals(norm) || pkg.equals(norm) || pkg.startsWith(norm + "/")) {
                return true;
            }
        }
        return false;
    }

    // -- low level lexing helpers -------------------------------------------

    private static int skipWhitespace(String source, int i) {
        int n = source.length();
        while (i < n && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipLineComment(String source, int i) {
        int n = source.length();
        while (i < n && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String source, int i) {
        int n = source.length();
        int end = source.indexOf("*/", i + 2);
        return end < 0 ? n : end + 2;
    }

    private static int skipLiteral(String source, int i, char quote) {
        int n = source.length();
        i++;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return i;
    }

    /** Returns the index after the leading Java identifier (including dots for
     *  qualified names) that starts at {@code i}. */
    private static int readIdentifier(String source, int i) {
        int n = source.length();
        if (i >= n || !Character.isJavaIdentifierStart(source.charAt(i))) {
            return i;
        }
        i++;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '.' || Character.isJavaIdentifierPart(c)) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }
}