package com.marketpix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageFilesTests {

    @TempDir
    Path tempDirectory;

    @Test
    void readsFormatsJavaSupportsWithoutImageMagick() throws Exception {
        for (String format : new String[]{"png", "jpg", "bmp", "gif", "tiff"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(landscape(), format, output), format + " writer missing");

            BufferedImage read = ImageFiles.readOriented(output.toByteArray(), "photo." + format);

            assertEquals(40, read.getWidth(), format);
            assertEquals(20, read.getHeight(), format);
        }
    }

    @Test
    void rejectsDataThatIsNotAnImage() {
        assertThrows(IOException.class, () -> ImageFiles.readOriented(new byte[]{1, 2, 3, 4}, "broken.jpg"));
    }

    /** Runs where ImageMagick is installed (the Docker image, or MARKETPIX_IMAGEMAGICK locally). */
    @Test
    void convertsIphoneHeicAndAvifWithImageMagick() throws Exception {
        assumeTrue(ImageMagickConverter.isAvailable(), "ImageMagick is not installed");
        for (String format : new String[]{"heic", "avif"}) {
            Path png = tempDirectory.resolve("source.png");
            Path converted = tempDirectory.resolve("photo." + format);
            ImageIO.write(landscape(), "png", png.toFile());
            Process process = new ProcessBuilder(
                    ImageMagickConverter.executable().orElseThrow(), png.toString(), converted.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(tempDirectory.resolve("magick.log").toFile())
                    .start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0,
                    "could not create a " + format + " sample: " + Files.readString(tempDirectory.resolve("magick.log")));

            BufferedImage read = ImageFiles.readOriented(Files.readAllBytes(converted), converted.getFileName().toString());

            assertEquals(40, read.getWidth(), format);
            assertEquals(20, read.getHeight(), format);
            Color left = new Color(read.getRGB(5, 10));
            assertTrue(left.getRed() > 180 && left.getBlue() < 90, format + " colors survive: " + left);
        }
    }

    /** Red on the left, blue on the right, so a wrong rotation or color shift shows. */
    private static BufferedImage landscape() {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, x < 20 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }
        return image;
    }
}
