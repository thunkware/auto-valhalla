package io.github.thunkware.auto.valhalla.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutoValhallaSourceRewriterTest {

    @Test
    void findsTopLevelTypesWithAnnotationFlags() {
        String source = "package demo;\n"
                + "\n"
                + "import io.github.thunkware.auto.valhalla.api.AutoValhalla;\n"
                + "\n"
                + "@AutoValhalla\n"
                + "public final class Point {\n"
                + "    // a nested class here must not be seen as top-level\n"
                + "    static class Helper {\n"
                + "    }\n"
                + "}\n"
                + "\n"
                + "@Deprecated\n"
                + "public record R(int x) {\n"
                + "}\n"
                + "\n"
                + "final class Shade {\n"
                + "}";

        List<AutoValhallaSourceRewriter.TypeDeclaration> types =
                AutoValhallaSourceRewriter.topLevelTypes(source);

        assertEquals(3, types.size(), "top-level types: Point, R, Shade");
        AutoValhallaSourceRewriter.TypeDeclaration point = types.get(0);
        assertEquals("Point", point.name);
        assertEquals("class", point.kind);
        assertTrue(point.annotated, "Point carries @AutoValhalla");

        AutoValhallaSourceRewriter.TypeDeclaration record = types.get(1);
        assertEquals("R", record.name);
        assertEquals("record", record.kind);
        assertFalse(record.annotated, "only @Deprecated is attached");

        AutoValhallaSourceRewriter.TypeDeclaration shade = types.get(2);
        assertEquals("Shade", shade.name);
        assertFalse(shade.annotated);
    }

    @Test
    void ignoresClassInCommentsAndStrings() {
        String source = "package demo;\n"
                + "// public class NotAType {}\n"
                + "/* class AlsoNotAType {} */\n"
                + "public final class Real {\n"
                + "    String s = \"class NotAType\";\n"
                + "}\n";

        List<AutoValhallaSourceRewriter.TypeDeclaration> types =
                AutoValhallaSourceRewriter.topLevelTypes(source);

        assertEquals(1, types.size());
        assertEquals("Real", types.get(0).name);
    }

    @Test
    void adaptsOnlySelectedDeclarations() {
        String source = "package demo;\n"
                + "@AutoValhalla\n"
                + "public final class Point {\n"
                + "}\n"
                + "\n"
                + "final class Shade {\n"
                + "}\n"
                + "\n"
                + "public record R(int x) {\n"
                + "}\n";

        String adapted = AutoValhallaSourceRewriter.adapt(source, List.of("Point", "R"));

        assertTrue(adapted.contains("public final value class Point"),
                "selected class re-declared as value class");
        assertTrue(adapted.contains("public value record R"),
                "selected record re-declared as value record");
        assertTrue(adapted.contains("final class Shade"),
                "unselected class left untouched");
    }

    @Test
    void adaptInsertsValueRightBeforeKeyword() {
        String source = "@Deprecated\npublic sealed abstract class Base {\n}\n"
                + "final class Leaf extends Base {\n}\n";
        String adapted = AutoValhallaSourceRewriter.adapt(source, List.of("Leaf"));
        assertTrue(adapted.contains("final value class Leaf"));
        assertTrue(adapted.contains("public sealed abstract class Base"));
    }

    @Test
    void patternMatchesMirrorsAgentSemantics() {
        // star matches everything
        assertTrue(AutoValhallaSourceRewriter.patternMatches(List.of("*"), "any/pkg/Cls"));
        assertFalse(AutoValhallaSourceRewriter.patternMatches(List.of(), "any/pkg/Cls"));
        // exact class name (dot or slash form)
        assertTrue(AutoValhallaSourceRewriter.patternMatches(
                List.of("com.example.Point"), "com/example/Point"));
        assertFalse(AutoValhallaSourceRewriter.patternMatches(
                List.of("com.example.Point"), "com/example/Points"));
        // package without trailing dot matches the exact package and recursively
        assertTrue(AutoValhallaSourceRewriter.patternMatches(
                List.of("com.example"), "com/example/Point"));
        assertTrue(AutoValhallaSourceRewriter.patternMatches(
                List.of("com.example"), "com/example/deep/Nested"));
        // trailing slash is a package-prefix match
        assertTrue(AutoValhallaSourceRewriter.patternMatches(
                List.of("io/lib/"), "io/lib/deep/Nested"));
        assertFalse(AutoValhallaSourceRewriter.patternMatches(
                List.of("com.example"), "org/foo/Bar"));
        // bare word: exact class or recursive package match
        assertTrue(AutoValhallaSourceRewriter.patternMatches(
                List.of("sample"), "sample/Foo"));
        assertTrue(AutoValhallaSourceRewriter.patternMatches(
                List.of("sample"), "sample/sub/Foo"));
        assertFalse(AutoValhallaSourceRewriter.patternMatches(
                List.of("sample"), "other/Foo"));
    }
}