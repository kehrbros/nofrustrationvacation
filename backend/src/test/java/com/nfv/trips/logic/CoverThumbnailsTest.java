package com.nfv.trips.logic;

import com.nfv.trips.entities.TripCoverImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Reduced copies of title photos: size, format, EXIF orientation, undecodable data. */
class CoverThumbnailsTest {

    @Test
    void generatedPhotoIsScaledToJpeg() throws IOException {
        byte[] original = encode(image(1536, 1024, BufferedImage.TYPE_INT_RGB), "jpeg");

        TripCoverImage.Thumbnail thumbnail = CoverThumbnails.create("image/jpeg", original).orElseThrow();

        assertThat(thumbnail.contentType()).isEqualTo("image/jpeg");
        BufferedImage decoded = decode(thumbnail.content());
        assertThat(decoded.getWidth()).isEqualTo(768);
        assertThat(decoded.getHeight()).isEqualTo(512);
    }

    @Test
    void portraitFitsIntoTheHeight() throws IOException {
        byte[] original = encode(image(1000, 2000, BufferedImage.TYPE_INT_RGB), "png");

        BufferedImage decoded = decode(CoverThumbnails.create("image/png", original).orElseThrow().content());

        assertThat(decoded.getWidth()).isEqualTo(256);
        assertThat(decoded.getHeight()).isEqualTo(512);
    }

    @Test
    void smallPhotoIsNotEnlargedAndAlphaBecomesWhite() throws IOException {
        BufferedImage transparent = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB);
        byte[] original = encode(transparent, "png");

        BufferedImage decoded = decode(CoverThumbnails.create("image/png", original).orElseThrow().content());

        assertThat(decoded.getWidth()).isEqualTo(100);
        assertThat(decoded.getHeight()).isEqualTo(50);
        Color corner = new Color(decoded.getRGB(0, 0));
        assertThat(corner.getRed()).isGreaterThan(240);
        assertThat(corner.getGreen()).isGreaterThan(240);
    }

    @Test
    void exifOrientationRotatesThePhoto() throws IOException {
        // stored landscape, camera says "rotate 90° clockwise" (orientation 6): upright it is a portrait
        byte[] original = withExifOrientation(encode(image(800, 400, BufferedImage.TYPE_INT_RGB), "jpeg"), 6);
        assertThat(CoverThumbnails.ExifOrientation.read(original)).isEqualTo(6);

        BufferedImage decoded = decode(CoverThumbnails.create("image/jpeg", original).orElseThrow().content());

        assertThat(decoded.getWidth()).isEqualTo(256);
        assertThat(decoded.getHeight()).isEqualTo(512);
        // the red left edge of the stored image is on top after rotating clockwise
        Color top = new Color(decoded.getRGB(128, 2));
        assertThat(top.getRed()).isGreaterThan(200);
        assertThat(top.getBlue()).isLessThan(80);
    }

    @Test
    void undecodableDataGivesNoThumbnail() {
        assertThat(CoverThumbnails.create("image/jpeg", new byte[]{1, 2, 3})).isEmpty();
        assertThat(CoverThumbnails.create("image/webp", "RIFF....WEBPVP8 ".getBytes())).isEmpty();
        assertThat(CoverThumbnails.ExifOrientation.read(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})).isEqualTo(1);
    }

    /** Blue image with a red stripe on the left edge. */
    private static BufferedImage image(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width / 8, height);
        graphics.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] content) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(content));
    }

    /** Inserts an APP1 EXIF block (big endian, one IFD entry: orientation) right after SOI. */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
                'M', 'M', 0, 42, 0, 0, 0, 8,          // header, IFD at offset 8
                0, 1,                                   // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // orientation, SHORT, count 1
                0, 0, 0, 0                              // no next IFD
        };
        int length = 2 + 6 + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
