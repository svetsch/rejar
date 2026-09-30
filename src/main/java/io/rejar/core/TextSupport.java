package io.rejar.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

/** Text/binary detection, decoding, hex dumps and bytecode listings. */
public final class TextSupport {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final Set<String> BINARY_EXTENSIONS = Set.of("class", "jar", "zip", "war", "ear", "so", "dll",
            "dylib", "jnilib", "exe", "ico", "ttf", "otf", "woff", "woff2", "gz", "tgz", "bin", "dat", "ser", "jks",
            "p12", "keystore", "pdf", "mp3", "wav", "ogg");

    private TextSupport() {
    }

    public static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImage(String path) {
        return IMAGE_EXTENSIONS.contains(extension(path));
    }

    public static boolean isText(byte[] data) {
        int n = Math.min(data.length, 8192);
        int control = 0;
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) {
                return false;
            }
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                control++;
            }
        }
        return control <= n / 50;
    }

    public static boolean isProbablyText(String path, byte[] data) {
        return !BINARY_EXTENSIONS.contains(extension(path)) && !isImage(path) && isText(data);
    }

    /** Decodes as UTF-8, falling back to ISO-8859-1 for invalid sequences. */
    public static String decode(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, StandardCharsets.ISO_8859_1);
        }
    }

    public static boolean isUtf8(byte[] data) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    public static String hexDump(byte[] data, int limit) {
        int n = Math.min(data.length, limit);
        StringBuilder sb = new StringBuilder(n * 4 + 100);
        for (int offset = 0; offset < n; offset += 16) {
            sb.append(String.format("%08x  ", offset));
            StringBuilder ascii = new StringBuilder(16);
            for (int i = 0; i < 16; i++) {
                if (offset + i < n) {
                    int b = data[offset + i] & 0xFF;
                    sb.append(String.format("%02x ", b));
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        if (data.length > n) {
            sb.append("... (").append(data.length - n).append(" more bytes)\n");
        }
        return sb.toString();
    }

    /** ASM textual listing of a class file. */
    public static String bytecode(byte[] classBytes) {
        StringWriter sw = new StringWriter();
        try {
            new ClassReader(classBytes).accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(sw)), 0);
        } catch (RuntimeException e) {
            sw.append("\n// Cannot read class file: ").append(String.valueOf(e));
        }
        return sw.toString();
    }
}
