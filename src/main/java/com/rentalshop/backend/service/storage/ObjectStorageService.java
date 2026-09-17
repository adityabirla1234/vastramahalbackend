package com.rentalshop.backend.service.storage;

/**
 * Storage backend for item photos. The DB never holds binary image data
 * (see schema.sql's comment on item_images.image_url) -- only a storage
 * key, resolved to a public URL through {@link #publicUrl(String)} at read
 * time.
 *
 * Two implementations are provided, selected via {@code app.storage.provider}:
 *   - {@code local} (default): writes to local disk, served back out via a
 *     static resource handler. Good for local dev and for tests -- zero
 *     external dependencies, nothing to configure.
 *   - {@code s3}: any S3-compatible bucket (AWS S3, Cloudflare R2, Backblaze
 *     B2, MinIO, ...) via the AWS SDK. This is what a real deployment should
 *     use, since the free-tier host running the backend (Render/Railway/etc,
 *     per application.yml's comments) almost certainly has an ephemeral
 *     filesystem -- local disk storage does NOT survive a redeploy there.
 */
public interface ObjectStorageService {

    /**
     * Uploads (or overwrites) the object at {@code key}.
     *
     * @param key         storage key, e.g. "items/DRESS-001/3f2a1c.webp"
     * @param content     raw bytes to store
     * @param contentType MIME type, e.g. "image/webp"
     */
    void upload(String key, byte[] content, String contentType);

    /** Deletes the object at {@code key}. Safe to call on a key that no longer exists. */
    void delete(String key);

    /** Resolves a storage key to a fully-qualified, publicly fetchable URL. */
    String publicUrl(String key);
}
