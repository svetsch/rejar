import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Renders the ReJar icon (designed on a 256 grid) at several sizes: syntax-colored code lines under a magnifier.
 * Also writes the native packaging icons (Windows .ico, macOS .icns, Linux .png) used by jpackage.
 * Usage: java tools/IconGen.java src/main/resources/io/rejar/ui/icons src/packaging
 */
public class IconGen {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        dir.mkdirs();
        Map<Integer, byte[]> pngs = new TreeMap<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256, 512, 1024}) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.scale(size / 256.0, size / 256.0);
            draw(g, size <= 24);
            g.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bytes);
            pngs.put(size, bytes.toByteArray());
            if (size <= 512) {
                Files.write(new File(dir, "rejar-" + size + ".png").toPath(), bytes.toByteArray());
            }
        }
        if (a.length > 1) {
            File packaging = new File(a[1]);
            packaging.mkdirs();
            Files.write(new File(packaging, "rejar.png").toPath(), pngs.get(512));
            Files.write(new File(packaging, "rejar.ico").toPath(), ico(pngs, 16, 24, 32, 48, 64, 128, 256));
            Files.write(new File(packaging, "rejar.icns").toPath(), icns(pngs));
        }
    }

    /** Windows icon: directory of PNG-compressed images (supported since Vista). */
    static byte[] ico(Map<Integer, byte[]> pngs, int... sizes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int size : sizes) {
            byte[] png = pngs.get(size);
            header.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size))
                    .put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32)
                    .putInt(png.length).putInt(offset);
            offset += png.length;
        }
        out.write(header.array());
        for (int size : sizes) {
            out.write(pngs.get(size));
        }
        return out.toByteArray();
    }

    /** macOS icon: PNG entries (icp4..ic10). */
    static byte[] icns(Map<Integer, byte[]> pngs) throws IOException {
        String[][] types = {{"icp4", "16"}, {"icp5", "32"}, {"icp6", "64"}, {"ic07", "128"}, {"ic08", "256"},
                {"ic09", "512"}, {"ic10", "1024"}};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        for (String[] t : types) {
            byte[] png = pngs.get(Integer.parseInt(t[1]));
            data.writeBytes(t[0]);
            data.writeInt(png.length + 8);
            data.write(png);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream file = new DataOutputStream(out);
        file.writeBytes("icns");
        file.writeInt(body.size() + 8);
        file.write(body.toByteArray());
        return out.toByteArray();
    }

    static void draw(Graphics2D g, boolean small) {
        // background: dark slate rounded square with a soft top highlight
        g.setPaint(new GradientPaint(8, 8, new Color(0x1E293B), 248, 248, new Color(0x3B4A63)));
        g.fill(new RoundRectangle2D.Double(8, 8, 240, 240, 56, 56));
        g.setPaint(new GradientPaint(0, 8, new Color(255, 255, 255, 36), 0, 128, new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(8, 8, 240, 120, 56, 56));

        // syntax-colored code lines (x, y, width, color); fewer and thicker at small sizes
        int[][] lines = small
                ? new int[][]{{46, 58, 100, 0x60A5FA}, {46, 98, 140, 0x4ADE80}, {46, 138, 70, 0xF59E0B}, {46, 178, 60, 0xC084FC}}
                : new int[][]{{44, 50, 56, 0xC084FC}, {108, 50, 76, 0x60A5FA}, {64, 80, 112, 0x4ADE80},
                        {64, 110, 48, 0xF59E0B}, {120, 110, 64, 0xE2E8F0}, {64, 140, 60, 0x60A5FA},
                        {44, 170, 40, 0xC084FC}, {44, 200, 70, 0x94A3B8}};
        double h = small ? 24 : 16;
        for (int[] l : lines) {
            g.setColor(new Color(l[3]));
            g.fill(new RoundRectangle2D.Double(l[0], l[1], l[2], h, h, h));
        }

        // magnifier
        double lx = 150, ly = 146, lr = 50;
        g.setColor(new Color(255, 255, 255, 70));
        g.fill(new Ellipse2D.Double(lx - lr, ly - lr, 2 * lr, 2 * lr));
        g.setColor(new Color(0xF8FAFC));
        g.setStroke(new BasicStroke(small ? 20 : 15, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(lx - lr, ly - lr, 2 * lr, 2 * lr));
        g.setColor(new Color(0xF59E0B));
        g.setStroke(new BasicStroke(small ? 30 : 24, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(lx + lr * 0.78, ly + lr * 0.78, 212, 210));
    }
}
