package com.rentalshop.backend.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Default storage backend: writes under {@code app.storage.local.dir} and
 * serves it back out via the static resource handler registered in
 * {@code WebMvcConfig} at {@code app.storage.local.public-base-url}.
 *
 * Deliberately the {@code matchIfMissing} default so the app runs with zero
 * storage configuration out of the box -- switch to {@code s3} for any
 * deployment where the filesystem isn't durable (see interface Javadoc).
 */
@Service
@ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalDiskObjectStorageService implements ObjectStorageService {

    private final Path rootDir;
    private final String publicBaseUrl;

    public LocalDiskObjectStorageService(
            @Value("${app.storage.local.dir:./uploads}") String dir,
            @Value("${app.storage.local.public-base-url:http://localhost:8080/uploads}") String publicBaseUrl) {
        this.rootDir = Paths.get(dir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create local storage directory: " + this.rootDir, e);
        }
    }

    @Override
    public void upload(String key, byte[] content, String contentType) {
        // contentType is unused here -- the local filesystem has no notion of
        // content-type metadata; it's inferred from the file extension when
        // served back out. Kept as a parameter so both implementations share
        // one interface.
        Path target = resolveWithinRoot(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write local file for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolveWithinRoot(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete local file for key: " + key, e);
        }
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }

    /**
     * Resolves {@code key} against the storage root and rejects anything
     * that would escape it (e.g. a key containing "../"). Keys are always
     * generated server-side (see ItemImageService), never taken verbatim
     * from client input, but this guard costs nothing and removes any doubt.
     */
    private Path resolveWithinRoot(String key) {
        Path resolved = rootDir.resolve(key).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return resolved;
    }
}
