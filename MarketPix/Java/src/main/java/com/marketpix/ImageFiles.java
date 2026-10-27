package com.marketpix;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Reading, orienting, resizing and encoding images without touching their content. */
public final class ImageFiles {

    private ImageFiles() {
    }

    /** Decodes any supported image, applies its EXIF orientation and returns it as opaque RGB. */
    public static BufferedImage readOriented(Path path) throws IOException {
        return readOriented(Files.readAllBytes(path), path.getFileName().toString());
    }

    /**
     * Java reads JPEG, PNG, GIF, TIFF, WebP, BMP and PSD directly; anything else (HEIC, AVIF, ...) is
     * converted with ImageMagick, which also turns it upright. {@code fileName} may be null.
     */
    public static BufferedImage readOriented(byte[] source, String fileName) throws IOException {
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException | RuntimeException exception) {
            decoded = null;
        }
        if (decoded != null) {
            return toRgb(applyExifOrientation(decoded, readExifOrientation(source)));
        }
        BufferedImage converted = ImageIO.read(new ByteArrayInputStream(ImageMagickConverter.toPng(source, fileName)));
        if (converted == null) {
            throw new IOException("The image could not be converted.");
        }
        return toRgb(converted);
    }

    public static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    /** Downscales so the long edge is at most {@code maxEdge}; never upscales. */
    public static BufferedImage limitLongEdge(BufferedImage source, int maxEdge) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= maxEdge) {
            return source;
        }
        double scale = (double) maxEdge / longEdge;
        return resize(source,
                Math.max(1, (int) Math.round(source.getWidth() * scale)),
                Math.max(1, (int) Math.round(source.getHeight() * scale)));
    }

    /** High-quality resize; large reductions are done in halving steps to avoid aliasing. */
    public static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        int currentWidth = source.getWidth();
        int currentHeight = source.getHeight();
        while (currentWidth / 2 >= width && currentHeight / 2 >= height) {
            currentWidth /= 2;
            currentHeight /= 2;
            current = draw(current, currentWidth, currentHeight);
        }
        return currentWidth == width && currentHeight == height ? current : draw(current, width, height);
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    public static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("No PNG encoder is available.");
        }
        return bytes.toByteArray();
    }

    public static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        BufferedImage rgb = toRgb(image);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG encoder is available.");
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(rgb, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    static BufferedImage applyExifOrientation(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return source;
        }
        boolean swapDimensions = orientation >= 5;
        int targetWidth = swapDimensions ? source.getHeight() : source.getWidth();
        int targetHeight = swapDimensions ? source.getWidth() : source.getHeight();
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int targetX;
                int targetY;
                switch (orientation) {
                    case 2 -> {
                        targetX = source.getWidth() - 1 - x;
                        targetY = y;
                    }
                    case 3 -> {
                        targetX = source.getWidth() - 1 - x;
                        targetY = source.getHeight() - 1 - y;
                    }
                    case 4 -> {
                        targetX = x;
                        targetY = source.getHeight() - 1 - y;
                    }
                    case 5 -> {
                        targetX = y;
                        targetY = x;
                    }
                    case 6 -> {
                        targetX = source.getHeight() - 1 - y;
                        targetY = x;
                    }
                    case 7 -> {
                        targetX = source.getHeight() - 1 - y;
                        targetY = source.getWidth() - 1 - x;
                    }
                    case 8 -> {
                        targetX = y;
                        targetY = source.getWidth() - 1 - x;
                    }
                    default -> {
                        targetX = x;
                        targetY = y;
                    }
                }
                target.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return target;
    }

    static int readExifOrientation(byte[] jpeg) {
        if (jpeg.length < 4 || jpeg[0] != (byte) 0xff || jpeg[1] != (byte) 0xd8) {
            return 1;
        }

        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if (jpeg[offset] != (byte) 0xff) {
                return 1;
            }
            int marker = jpeg[offset + 1] & 0xff;
            int segmentLength = readUnsignedShort(jpeg, offset + 2, false);
            if (segmentLength < 2 || offset + 2 + segmentLength > jpeg.length) {
                return 1;
            }
            int segmentStart = offset + 4;
            if (marker == 0xe1
                    && segmentLength >= 8
                    && jpeg[segmentStart] == 'E'
                    && jpeg[segmentStart + 1] == 'x'
                    && jpeg[segmentStart + 2] == 'i'
                    && jpeg[segmentStart + 3] == 'f'
                    && jpeg[segmentStart + 4] == 0
                    && jpeg[segmentStart + 5] == 0) {
                return readTiffOrientation(jpeg, segmentStart + 6, offset + 2 + segmentLength);
            }
            if (marker == 0xda || marker == 0xd9) {
                return 1;
            }
            offset += 2 + segmentLength;
        }
        return 1;
    }

    private static int readTiffOrientation(byte[] bytes, int tiffStart, int limit) {
        if (tiffStart + 8 > limit) {
            return 1;
        }
        boolean littleEndian;
        if (bytes[tiffStart] == 'I' && bytes[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (bytes[tiffStart] == 'M' && bytes[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return 1;
        }
        if (readUnsignedShort(bytes, tiffStart + 2, littleEndian) != 42) {
            return 1;
        }

        long directoryOffset = readUnsignedInt(bytes, tiffStart + 4, littleEndian);
        long directoryLong = tiffStart + directoryOffset;
        if (directoryLong < tiffStart || directoryLong + 2 > limit) {
            return 1;
        }
        int directory = (int) directoryLong;
        int entryCount = readUnsignedShort(bytes, directory, littleEndian);
        for (int index = 0; index < entryCount; index++) {
            int entry = directory + 2 + index * 12;
            if (entry + 12 > limit) {
                return 1;
            }
            if (readUnsignedShort(bytes, entry, littleEndian) == 0x0112
                    && readUnsignedShort(bytes, entry + 2, littleEndian) == 3
                    && readUnsignedInt(bytes, entry + 4, littleEndian) >= 1) {
                int orientation = readUnsignedShort(bytes, entry + 8, littleEndian);
                return orientation >= 1 && orientation <= 8 ? orientation : 1;
            }
        }
        return 1;
    }

    private static int readUnsignedShort(byte[] bytes, int offset, boolean littleEndian) {
        int first = bytes[offset] & 0xff;
        int second = bytes[offset + 1] & 0xff;
        return littleEndian ? first | (second << 8) : (first << 8) | second;
    }

    private static long readUnsignedInt(byte[] bytes, int offset, boolean littleEndian) {
        long first = readUnsignedShort(bytes, offset, littleEndian);
        long second = readUnsignedShort(bytes, offset + 2, littleEndian);
        return littleEndian ? first | (second << 16) : (first << 16) | second;
    }
}
