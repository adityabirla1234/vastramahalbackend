package com.rentalshop.backend.service.image;

public interface ImageProcessingService {

    /**
     * Resizes (if needed) and compresses an uploaded image for storage.
     *
     * @param original         raw uploaded bytes
     * @param originalFilename original filename, used only for logging/errors
     * @return the processed image, ready to hand to ObjectStorageService
     */
    ProcessedImage process(byte[] original, String originalFilename);

    record ProcessedImage(byte[] data, String contentType, String fileExtension) {
    }
}
