package com.rentalshop.backend.service.image;

import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Resizes uploaded photos to a sane max dimension and compresses them to
 * WebP (via the org.sejda.imageio:webp-imageio ImageIO plugin, added as a
 * dependency in pom.xml -- it registers a WebP ImageWriter backed by a
 * native libwebp binding for common platforms).
 *
 * WebP encoding depends on that native library loading correctly for the
 * host OS/architecture. If it doesn't (e.g. an unsupported platform, or the
 * plugin failing to register for any reason), this falls back to JPEG at
 * the same quality rather than failing the upload outright -- a shop owner
 * uploading a photo from their phone should never see "upload failed"
 * because of a codec problem on the server. The fallback is logged so it's
 * visible in ops, not silent.
 */
@Service
public class ThumbnailatorWebpImageProcessingService implements ImageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailatorWebpImageProcessingService.class);

    /** Longest edge, in pixels, after resize. Phone camera photos are almost always larger than this. */
    private static final int MAX_DIMENSION = 1600;

    @Value("${app.image.webp-quality:0.75}")
    private float quality;

    @Override
    public ProcessedImage process(byte[] original, String originalFilename) {
        BufferedImage probe;
        try {
            probe = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read uploaded file as an image: " + originalFilename, e);
        }
        if (probe == null) {
            throw new IllegalArgumentException("Uploaded file is not a recognized image format: " + originalFilename);
        }

        // Fit within MAX_DIMENSION on the longest edge without ever upscaling
        // a smaller original -- min() against the probed size handles that.
        int targetWidth = Math.min(probe.getWidth(), MAX_DIMENSION);
        int targetHeight = Math.min(probe.getHeight(), MAX_DIMENSION);

        try {
            return encode(original, targetWidth, targetHeight, "webp", "image/webp", "webp");
        } catch (Exception | UnsatisfiedLinkError e) {
            log.warn("WebP encoding unavailable ({}: {}); falling back to JPEG for '{}'.",
                    e.getClass().getSimpleName(), e.getMessage(), originalFilename);
            try {
                return encode(original, targetWidth, targetHeight, "jpg", "image/jpeg", "jpg");
            } catch (IOException io) {
                throw new IllegalStateException("Failed to process uploaded image: " + originalFilename, io);
            }
        }
    }

    private ProcessedImage encode(byte[] original, int width, int height,
                                   String outputFormat, String contentType, String extension) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(new ByteArrayInputStream(original))
                .size(width, height)
                .keepAspectRatio(true)
                .outputFormat(outputFormat)
                .outputQuality(quality)
                .toOutputStream(out);
        return new ProcessedImage(out.toByteArray(), contentType, extension);
    }
}
