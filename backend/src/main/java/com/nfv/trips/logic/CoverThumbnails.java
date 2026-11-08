package com.nfv.trips.logic;

import com.nfv.trips.entities.TripCoverImage;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Reduced copy of a title photo for lists of trips: at most {@value #MAX_WIDTH}×{@value #MAX_HEIGHT} pixels (enough
 * for the wide card of the current trip on phones with a dense display), JPEG. JPEG and PNG are decoded with the JDK;
 * other formats (WebP) and broken data give no thumbnail, then the original is shown. Honors the EXIF orientation of
 * JPEGs from phone cameras, which the browser applies to the original as well.
 */
@Slf4j
public final class CoverThumbnails {
    static final int MAX_WIDTH = 768;
    static final int MAX_HEIGHT = 512;
    private static final float QUALITY = 0.8f;

    private CoverThumbnails() {
    }

    public static Optional<TripCoverImage.Thumbnail> create(String contentType, byte[] content) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(content));
            if (source == null) {
                return Optional.empty();
            }
            int orientation = "image/jpeg".equals(contentType) ? ExifOrientation.read(content) : 1;
            return Optional.of(new TripCoverImage.Thumbnail("image/jpeg", jpeg(scale(source, orientation))));
        } catch (IOException | RuntimeException e) {
            log.info("No thumbnail for a title photo of type {}: {}", contentType, e.toString());
            return Optional.empty();
        }
    }

    /** Scaled into the maximum size (never enlarged), rotated per EXIF orientation, alpha on white. */
    private static BufferedImage scale(BufferedImage source, int orientation) {
        boolean swap = orientation >= 5 && orientation <= 8;
        int width = swap ? source.getHeight() : source.getWidth();
        int height = swap ? source.getWidth() : source.getHeight();
        double factor = Math.min(1, Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height));
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));

        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.transform(orientationTransform(orientation, targetWidth, targetHeight));
            graphics.scale(factor, factor);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** Maps the stored (scaled) image onto the upright target of size {@code w}×{@code h}. */
    private static AffineTransform orientationTransform(int orientation, int w, int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, w, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, w, h);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, h);
            default -> new AffineTransform();
        };
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Orientation tag (0x0112) from the EXIF block of a JPEG, 1 (upright) if there is none or it is unreadable. */
    static final class ExifOrientation {
        private ExifOrientation() {
        }

        static int read(byte[] jpeg) {
            try {
                return find(jpeg);
            } catch (RuntimeException e) {
                return 1;
            }
        }

        private static int find(byte[] b) {
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
                return 1;
            }
            int pos = 2;
            while (pos + 4 <= b.length && (b[pos] & 0xFF) == 0xFF) {
                int marker = b[pos + 1] & 0xFF;
                int length = u16(b, pos + 2, true);
                if (marker == 0xDA || marker == 0xD9) {
                    return 1; // image data starts, no EXIF before it
                }
                if (marker == 0xE1 && length >= 8 && pos + 10 <= b.length
                        && b[pos + 4] == 'E' && b[pos + 5] == 'x' && b[pos + 6] == 'i' && b[pos + 7] == 'f') {
                    return orientation(b, pos + 10);
                }
                pos += 2 + length;
            }
            return 1;
        }

        private static int orientation(byte[] b, int tiff) {
            boolean bigEndian = b[tiff] == 'M';
            int ifd = tiff + u32(b, tiff + 4, bigEndian);
            int entries = u16(b, ifd, bigEndian);
            for (int i = 0; i < entries; i++) {
                int entry = ifd + 2 + i * 12;
                if (u16(b, entry, bigEndian) == 0x0112) {
                    int value = u16(b, entry + 8, bigEndian);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
            return 1;
        }

        private static int u16(byte[] b, int pos, boolean bigEndian) {
            int first = b[pos] & 0xFF;
            int second = b[pos + 1] & 0xFF;
            return bigEndian ? first << 8 | second : second << 8 | first;
        }

        private static int u32(byte[] b, int pos, boolean bigEndian) {
            return bigEndian
                    ? u16(b, pos, true) << 16 | u16(b, pos + 2, true)
                    : u16(b, pos + 2, false) << 16 | u16(b, pos, false);
        }
    }
}
