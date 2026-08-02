// Generates the Nux app icon (a hex nut with a lightning bolt) headlessly, with
// nothing but the JDK. Run from the repo root:
//
//   java scripts/gen_icon.java
//
// Writes branding/nux-<size>.png (16..512; nux.png = the jpackage Linux icon), copies
// the window sizes into src/main/resources/com/nux/icons/, and emits the platform
// installer icons branding/nux.ico (Windows) + branding/nux.icns (macOS) — both
// formats accept embedded PNGs, so no external tool is needed.
// The vector source of truth is branding/nux.svg — keep the two in sync when redesigning.

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

void main() throws Exception {
    System.setProperty("java.awt.headless", "true");
    int[] sizes = {16, 32, 64, 128, 256, 512};
    Path branding = Path.of("branding");
    Path resourceDir = Path.of("src/main/resources/com/nux/icons");
    Files.createDirectories(branding);
    Files.createDirectories(resourceDir);

    Map<Integer, byte[]> pngs = new LinkedHashMap<>();
    for (int size : sizes) {
        BufferedImage image = render(size);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        pngs.put(size, bytes.toByteArray());
        Path out = branding.resolve("nux-" + size + ".png");
        Files.write(out, bytes.toByteArray());
        if (size <= 256) {
            Files.copy(out, resourceDir.resolve("nux-" + size + ".png"), StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("wrote " + out);
    }
    Files.copy(branding.resolve("nux-512.png"), branding.resolve("nux.png"), StandardCopyOption.REPLACE_EXISTING);
    System.out.println("wrote branding/nux.png (jpackage Linux icon)");

    Files.write(branding.resolve("nux.ico"), ico(pngs, 16, 32, 64, 256));
    System.out.println("wrote branding/nux.ico (jpackage Windows icon)");
    Files.write(branding.resolve("nux.icns"), icns(pngs));
    System.out.println("wrote branding/nux.icns (jpackage macOS icon)");
}

/** ICO container with PNG-compressed entries (supported since Windows Vista). Little-endian. */
byte[] ico(Map<Integer, byte[]> pngs, int... sizes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    le16(out, 0); // reserved
    le16(out, 1); // type: icon
    le16(out, sizes.length);
    int offset = 6 + 16 * sizes.length;
    for (int size : sizes) {
        byte[] png = pngs.get(size);
        out.write(size >= 256 ? 0 : size); // 0 means 256
        out.write(size >= 256 ? 0 : size);
        out.write(0); // palette colors
        out.write(0); // reserved
        le16(out, 1); // planes
        le16(out, 32); // bits per pixel
        le32(out, png.length);
        le32(out, offset);
        offset += png.length;
    }
    for (int size : sizes) {
        out.writeBytes(pngs.get(size));
    }
    return out.toByteArray();
}

/** ICNS container with PNG entries. Big-endian; each chunk is 4CC + length (incl. 8-byte header). */
byte[] icns(Map<Integer, byte[]> pngs) {
    Map<String, Integer> types = new LinkedHashMap<>();
    types.put("icp4", 16);
    types.put("icp5", 32);
    types.put("icp6", 64);
    types.put("ic07", 128);
    types.put("ic08", 256);
    types.put("ic09", 512);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (Map.Entry<String, Integer> e : types.entrySet()) {
        byte[] png = pngs.get(e.getValue());
        body.writeBytes(e.getKey().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        be32(body, png.length + 8);
        body.writeBytes(png);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes("icns".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    be32(out, body.size() + 8);
    out.writeBytes(body.toByteArray());
    return out.toByteArray();
}

void le16(ByteArrayOutputStream out, int v) {
    out.write(v & 0xFF);
    out.write((v >>> 8) & 0xFF);
}

void le32(ByteArrayOutputStream out, int v) {
    out.write(v & 0xFF);
    out.write((v >>> 8) & 0xFF);
    out.write((v >>> 16) & 0xFF);
    out.write((v >>> 24) & 0xFF);
}

void be32(ByteArrayOutputStream out, int v) {
    out.write((v >>> 24) & 0xFF);
    out.write((v >>> 16) & 0xFF);
    out.write((v >>> 8) & 0xFF);
    out.write(v & 0xFF);
}

BufferedImage render(int size) {
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    double s = size / 512.0;
    g.scale(s, s);

    // Background: dark rounded square
    g.setPaint(new GradientPaint(0, 0, new Color(0x2d333b), 0, 512, new Color(0x16191d)));
    g.fill(new RoundRectangle2D.Double(0, 0, 512, 512, 120, 120));

    // Hex nut: pointy-top hexagon, rounded corners via a fat round-join stroke
    Path2D hex = new Path2D.Double();
    double r = 175;
    for (int i = 0; i < 6; i++) {
        double angle = Math.toRadians(90 + i * 60);
        double x = 256 + r * Math.cos(angle);
        double y = 256 - r * Math.sin(angle);
        if (i == 0) {
            hex.moveTo(x, y);
        } else {
            hex.lineTo(x, y);
        }
    }
    hex.closePath();
    GradientPaint steel = new GradientPaint(0, 81, new Color(0xb6c0ca), 0, 431, new Color(0x5f6a75), true);
    g.setPaint(steel);
    g.fill(hex);
    g.setStroke(new BasicStroke(34, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(hex);

    // Bolt hole
    g.setColor(new Color(0x1b1f24));
    double hole = 92;
    g.fill(new Ellipse2D.Double(256 - hole, 256 - hole, hole * 2, hole * 2));

    // Lightning bolt
    Path2D bolt = new Path2D.Double();
    bolt.moveTo(296, 88);
    bolt.lineTo(176, 288);
    bolt.lineTo(246, 288);
    bolt.lineTo(218, 424);
    bolt.lineTo(338, 224);
    bolt.lineTo(268, 224);
    bolt.closePath();
    g.setColor(new Color(0xf0b429));
    g.fill(bolt);
    g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(bolt);

    g.dispose();
    return image;
}
