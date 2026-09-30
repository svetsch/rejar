package io.rejar.ui;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/** Regex based syntax highlighting producing RichTextFX style spans (CSS classes defined in rejar.css). */
public final class Highlighter {

    public enum Language { JAVA, BYTECODE, XML, JSON, PROPERTIES, MANIFEST, YAML, PLAIN }

    /** Above this size highlighting is skipped to keep the editor responsive. */
    public static final int MAX_LENGTH = 3_000_000;

    private static final String[] JAVA_KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "yield", "sealed",
            "permits", "non-sealed"};

    private static final Pattern JAVA = Pattern.compile(
            "(?<COMMENT>//[^\\n]*+|/\\*(?s:.*?)\\*/)"
                    + "|(?<TEXTBLOCK>\"\"\"(?s:.*?)(?<!\\\\)\"\"\")"
                    + "|(?<STRING>\"(?:[^\"\\\\\\n]++|\\\\.)*+\")"
                    + "|(?<CHAR>'(?:[^'\\\\\\n]++|\\\\.)*+')"
                    + "|(?<ANNOTATION>@(?!interface\\b)[\\w.]+)"
                    + "|(?<KEYWORD>\\b(?:" + String.join("|", JAVA_KEYWORDS) + ")\\b)"
                    + "|(?<LITERAL>\\b(?:true|false|null)\\b)"
                    + "|(?<NUMBER>\\b(?:0[xX][0-9a-fA-F_]+[lL]?|0[bB][01_]+[lL]?|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?[fFdDlL]?)\\b)"
                    + "|(?<TYPE>\\b[A-Z][A-Za-z0-9_$]*\\b)"
                    + "|(?<METHOD>\\b[a-z_$][\\w$]*(?=\\s*\\())"
                    + "|(?<BRACE>[{}()\\[\\]])"
                    + "|(?<OPERATOR>[=+\\-*/%<>!&|^~?:;,.])");

    private static final Pattern BYTECODE = Pattern.compile(
            "(?<COMMENT>//[^\\n]*+)"
                    + "|(?<STRING>\"(?:[^\"\\\\\\n]++|\\\\.)*+\")"
                    + "|(?<KEYWORD>\\b(?:public|private|protected|static|final|abstract|synchronized|volatile|transient|native|interface|class|enum|extends|implements|synthetic|bridge|varargs|deprecated)\\b)"
                    + "|(?<OPCODE>\\b[A-Z][A-Z0-9_]{1,}\\b)"
                    + "|(?<LABEL>\\bL\\d+\\b)"
                    + "|(?<ANNOTATION>@[\\w/$;.]+)"
                    + "|(?<NUMBER>\\b-?\\d+(?:\\.\\d+)?[LFD]?\\b)"
                    + "|(?<TYPE>\\bL?[a-z][\\w$]*(?:/[\\w$]+)+;?)");

    private static final Pattern XML = Pattern.compile(
            "(?<COMMENT><!--(?s:.*?)-->)"
                    + "|(?<CDATA><!\\[CDATA\\[(?s:.*?)]]>)"
                    + "|(?<PROLOG><\\?(?s:.*?)\\?>|<!DOCTYPE[^>]*>)"
                    + "|(?<TAG></?[\\w:.-]+|/?>)"
                    + "|(?<ATTR>\\b[\\w:.-]+(?=\\s*=\\s*[\"']))"
                    + "|(?<VALUE>\"[^\"]*\"|'[^']*')"
                    + "|(?<ENTITY>&[#\\w]+;)");

    private static final Pattern JSON = Pattern.compile(
            "(?<KEY>\"(?:[^\"\\\\]++|\\\\.)*+\"(?=\\s*:))"
                    + "|(?<STRING>\"(?:[^\"\\\\]++|\\\\.)*+\")"
                    + "|(?<LITERAL>\\b(?:true|false|null)\\b)"
                    + "|(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)"
                    + "|(?<BRACE>[{}\\[\\]])");

    private static final Pattern PROPERTIES = Pattern.compile(
            "(?<COMMENT>^[ \\t]*[#!][^\\n]*)"
                    + "|(?<KEY>^[ \\t]*(?:[^=:\\s#!\\\\]|\\\\.)(?:[^=:\\s\\\\]|\\\\.)*)"
                    + "|(?<OPERATOR>(?<=\\S)[ \\t]*[=:])"
                    + "|(?<ESCAPE>\\\\u[0-9a-fA-F]{4}|\\\\.)"
                    + "|(?<PLACEHOLDER>\\$\\{[^}\\n]*}|\\{\\d+})", Pattern.MULTILINE);

    private static final Pattern MANIFEST = Pattern.compile(
            "(?<KEY>^[\\w-]+(?=:))", Pattern.MULTILINE);

    private static final Pattern YAML = Pattern.compile(
            "(?<COMMENT>#[^\\n]*)"
                    + "|(?<KEY>^[ \\t-]*[\\w.\\-\"' ]+(?=:(?:\\s|$)))"
                    + "|(?<STRING>\"(?:[^\"\\\\\\n]++|\\\\.)*+\"|'[^'\\n]*')"
                    + "|(?<LITERAL>\\b(?:true|false|null|yes|no|on|off)\\b)"
                    + "|(?<NUMBER>\\b-?\\d+(?:\\.\\d+)?\\b)"
                    + "|(?<PLACEHOLDER>\\$\\{[^}\\n]*})"
                    + "|(?<OPERATOR>^---$|^\\.\\.\\.$)", Pattern.MULTILINE);

    private Highlighter() {
    }

    public static Language forPath(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
        if (name.equals("manifest.mf")) {
            return Language.MANIFEST;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "java", "groovy", "kt", "kts", "scala" -> Language.JAVA;
            case "xml", "xsd", "xsl", "xslt", "wsdl", "pom", "html", "htm", "xhtml", "fxml", "svg", "tld", "jsp" -> Language.XML;
            case "json" -> Language.JSON;
            case "properties", "list", "factories", "imports", "cfg", "conf", "ini" -> Language.PROPERTIES;
            case "yml", "yaml" -> Language.YAML;
            case "mf", "sf" -> Language.MANIFEST;
            default -> path.startsWith("META-INF/services/") ? Language.PROPERTIES : Language.PLAIN;
        };
    }

    public static StyleSpans<Collection<String>> compute(String text, Language language) {
        Pattern pattern = switch (language) {
            case JAVA -> JAVA;
            case BYTECODE -> BYTECODE;
            case XML -> XML;
            case JSON -> JSON;
            case PROPERTIES -> PROPERTIES;
            case MANIFEST -> MANIFEST;
            case YAML -> YAML;
            case PLAIN -> null;
        };
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        if (pattern == null || text.length() > MAX_LENGTH) {
            spans.add(Collections.emptyList(), text.length());
            return spans.create();
        }
        List<String> groups = groupNames(language);
        Matcher matcher = pattern.matcher(text);
        int last = 0;
        try {
            while (matcher.find()) {
                String styleClass = null;
                for (String group : groups) {
                    if (matcher.group(group) != null) {
                        styleClass = group.toLowerCase();
                        break;
                    }
                }
                if (styleClass == null || matcher.end() == matcher.start()) {
                    continue;
                }
                if (styleClass.equals("textblock")) {
                    styleClass = "string";
                } else if (styleClass.equals("char")) {
                    styleClass = "string";
                }
                spans.add(Collections.emptyList(), matcher.start() - last);
                spans.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
                last = matcher.end();
            }
        } catch (StackOverflowError e) {
            // pathological input: keep what was highlighted so far
        }
        spans.add(Collections.emptyList(), text.length() - last);
        return spans.create();
    }

    private static List<String> groupNames(Language language) {
        return switch (language) {
            case JAVA -> List.of("COMMENT", "TEXTBLOCK", "STRING", "CHAR", "ANNOTATION", "KEYWORD", "LITERAL", "NUMBER",
                    "TYPE", "METHOD", "BRACE", "OPERATOR");
            case BYTECODE -> List.of("COMMENT", "STRING", "KEYWORD", "OPCODE", "LABEL", "ANNOTATION", "NUMBER", "TYPE");
            case XML -> List.of("COMMENT", "CDATA", "PROLOG", "TAG", "ATTR", "VALUE", "ENTITY");
            case JSON -> List.of("KEY", "STRING", "LITERAL", "NUMBER", "BRACE");
            case PROPERTIES -> List.of("COMMENT", "KEY", "OPERATOR", "ESCAPE", "PLACEHOLDER");
            case MANIFEST -> List.of("KEY");
            case YAML -> List.of("COMMENT", "KEY", "STRING", "LITERAL", "NUMBER", "PLACEHOLDER", "OPERATOR");
            case PLAIN -> List.of();
        };
    }
}
