package io.rejar.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiLogicTest {

    @TempDir
    Path tmp;

    private static List<String> styled(String text, Highlighter.Language language, String styleClass) {
        StyleSpans<Collection<String>> spans = Highlighter.compute(text, language);
        assertEquals(text.length(), spans.length());
        List<String> result = new ArrayList<>();
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            if (span.getStyle().contains(styleClass)) {
                result.add(text.substring(pos, pos + span.getLength()));
            }
            pos += span.getLength();
        }
        return result;
    }

    @Test
    void javaHighlighting() {
        String src = """
                package a; // comment
                @Deprecated
                public class Foo<T> extends Bar {
                    String s = "if \\" else"; char c = '\\'';
                    int n = 0x1F + 12L;
                    /* block
                       comment */
                    Object o = null;
                    String t = \"\"\"
                        text block
                        \"\"\";
                    void run() { call(); }
                }
                """;
        assertTrue(styled(src, Highlighter.Language.JAVA, "keyword").containsAll(List.of("package", "public", "class", "extends", "int", "void")));
        assertEquals(List.of("// comment", "/* block\n       comment */"), styled(src, Highlighter.Language.JAVA, "comment"));
        List<String> strings = styled(src, Highlighter.Language.JAVA, "string");
        assertTrue(strings.contains("\"if \\\" else\""), strings.toString());
        assertTrue(strings.contains("'\\''"), strings.toString());
        assertTrue(strings.stream().anyMatch(s -> s.contains("text block")), strings.toString());
        assertEquals(List.of("@Deprecated"), styled(src, Highlighter.Language.JAVA, "annotation"));
        assertTrue(styled(src, Highlighter.Language.JAVA, "type").containsAll(List.of("Foo", "Bar", "String", "Object")));
        assertEquals(List.of("0x1F", "12L"), styled(src, Highlighter.Language.JAVA, "number"));
        assertEquals(List.of("null"), styled(src, Highlighter.Language.JAVA, "literal"));
        assertTrue(styled(src, Highlighter.Language.JAVA, "method").containsAll(List.of("run", "call")));
    }

    @Test
    void resourceHighlighting() {
        String xml = "<!-- c --><project x=\"1\">text</project>";
        assertEquals(List.of("<project", ">", "</project>"), styled(xml, Highlighter.Language.XML, "tag"));
        assertEquals(List.of("x"), styled(xml, Highlighter.Language.XML, "attr"));
        assertEquals(List.of("\"1\""), styled(xml, Highlighter.Language.XML, "value"));
        assertEquals(List.of("<!-- c -->"), styled(xml, Highlighter.Language.XML, "comment"));
        assertEquals(List.of("server.port", "name"),
                styled("# c\nserver.port=8080\nname : x\n", Highlighter.Language.PROPERTIES, "key"));
        assertEquals(List.of("\"k\""), styled("{\"k\": \"v\", \"n\": 1}".replace("\"n\": 1", "\"n\"1"), Highlighter.Language.JSON, "key"));
        assertEquals(Highlighter.Language.MANIFEST, Highlighter.forPath("META-INF/MANIFEST.MF"));
        assertEquals(Highlighter.Language.PROPERTIES, Highlighter.forPath("META-INF/services/java.sql.Driver"));
        assertEquals(Highlighter.Language.YAML, Highlighter.forPath("BOOT-INF/classes/application.yml"));
    }

    @Test
    void lineDiff() {
        boolean[][] d = CompareTab.diff(List.of("a", "b", "c", "d"), List.of("a", "x", "c", "d", "e"));
        assertArrayEquals(new boolean[]{false, true, false, false}, d[0]);
        assertArrayEquals(new boolean[]{false, true, false, false, true}, d[1]);
    }

    @Test
    void newJarNames() throws IOException {
        Path jar = Files.createFile(tmp.resolve("app-1.0.jar"));
        assertEquals("app-1.0-patched.jar", SaveDialog.defaultTarget(jar).getFileName().toString());
        Files.createFile(tmp.resolve("app-1.0-patched.jar"));
        assertEquals("app-1.0-patched2.jar", SaveDialog.defaultTarget(jar).getFileName().toString());
        assertEquals("app-1.0-patched2.jar", SaveDialog.defaultTarget(tmp.resolve("app-1.0-patched.jar")).getFileName().toString());
    }
}
