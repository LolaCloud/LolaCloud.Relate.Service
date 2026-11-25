package com.miranda.luna.relate.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class RelateStorageService {

    private static final Logger logger = LoggerFactory.getLogger(RelateStorageService.class);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String bucketName;
    private final String storeApiUrl;

    @Autowired
    public RelateStorageService(
            @Value("${lola.relate.s3.endpoint:http://127.0.0.1:9000}") String endpoint,
            @Value("${lola.relate.s3.region:us-east-1}") String region,
            @Value("${lola.relate.s3.access-key:lola_admin}") String accessKey,
            @Value("${lola.relate.s3.secret-key:lola_admin_secret}") String secretKey,
            @Value("${lola.relate.s3.bucket-name:lola-relate-backups}") String bucketName,
            @Value("${lola.relate.store.api-url:http://localhost:8086}") String storeApiUrl,
            ObjectMapper objectMapper,
            @Autowired(required = false) S3Client s3Client,
            @Autowired(required = false) S3Presigner s3Presigner
    ) {
        this.bucketName = bucketName;
        this.storeApiUrl = storeApiUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();

        if (s3Client != null) {
            this.s3Client = s3Client;
        } else {
            this.s3Client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .serviceConfiguration(s3Configuration)
                    .build();
        }

        if (s3Presigner != null) {
            this.s3Presigner = s3Presigner;
        } else {
            this.s3Presigner = S3Presigner.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .serviceConfiguration(s3Configuration)
                    .build();
        }
    }

    public RelateStorageService(
            S3Client s3Client,
            S3Presigner s3Presigner,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String bucketName,
            String storeApiUrl
    ) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.objectMapper = objectMapper;
        this.bucketName = bucketName;
        this.storeApiUrl = storeApiUrl;
    }

    public String getBucketName() {
        return bucketName;
    }

    public S3Client getS3Client() {
        return s3Client;
    }

    public S3Presigner getS3Presigner() {
        return s3Presigner;
    }

    public void ensureBackupBucketExists() {
        boolean bucketExists = false;
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            bucketExists = true;
            logger.debug("Backup bucket '{}' exists and is accessible", bucketName);
        } catch (NoSuchBucketException e) {
            logger.info("Backup bucket '{}' does not exist in S3 (NoSuchBucketException)", bucketName);
            bucketExists = false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                logger.info("Backup bucket '{}' does not exist in S3 (404 Not Found)", bucketName);
                bucketExists = false;
            } else {
                logger.warn("S3 headBucket returned error code {}: {}", e.statusCode(), e.getMessage());
                bucketExists = false;
            }
        } catch (Exception e) {
            logger.warn("Failed checking S3 bucket '{}' existence: {}", bucketName, e.getMessage());
            bucketExists = false;
        }

        if (!bucketExists) {
            createBackupBucket();
        }
    }

    private void createBackupBucket() {
        boolean registeredInStore = false;
        if (storeApiUrl != null && !storeApiUrl.isBlank()) {
            try {
                String cleanStoreUrl = storeApiUrl.endsWith("/") ? storeApiUrl.substring(0, storeApiUrl.length() - 1) : storeApiUrl;
                Map<String, Object> payload = Map.of(
                        "name", bucketName,
                        "accessPolicy", "PRIVATE",
                        "isProtected", true
                );
                String jsonBody = objectMapper.writeValueAsString(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(cleanStoreUrl + "/api/v1/store/buckets"))
                        .timeout(Duration.ofSeconds(4))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    logger.info("Successfully registered backup bucket '{}' via Lola Store", bucketName);
                    registeredInStore = true;
                } else if (response.statusCode() == 409) {
                    logger.info("Backup bucket '{}' already registered in Lola Store", bucketName);
                    registeredInStore = true;
                } else {
                    logger.warn("Lola Store create bucket returned HTTP {}: {}", response.statusCode(), response.body());
                }
            } catch (Exception e) {
                logger.warn("Could not register bucket via Lola Store at {}: {}", storeApiUrl, e.getMessage());
            }
        }

        if (!registeredInStore) {
            try {
                logger.info("Attempting direct S3 bucket creation for '{}'", bucketName);
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
                logger.info("Successfully created backup bucket '{}' via S3 client", bucketName);
            } catch (Exception e) {
                logger.warn("Failed to create S3 bucket '{}' directly: {}", bucketName, e.getMessage());
            }
        }
    }

    public void uploadSnapshot(String s3Key, byte[] data, String contentType) {
        String effectiveContentType = (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream";
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(effectiveContentType)
                .contentLength((long) data.length)
                .build();
        s3Client.putObject(putRequest, RequestBody.fromBytes(data));
        logger.info("Uploaded snapshot to s3://{}/{} ({} bytes)", bucketName, s3Key, data.length);
    }

    public void uploadSnapshot(String s3Key, Path file, String contentType) {
        try {
            long size = Files.size(file);
            String effectiveContentType = (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream";
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(effectiveContentType)
                    .contentLength(size)
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromFile(file));
            logger.info("Uploaded snapshot file {} to s3://{}/{} ({} bytes)", file, bucketName, s3Key, size);
        } catch (IOException e) {
            throw new RuntimeException("Failed reading snapshot file for S3 upload: " + e.getMessage(), e);
        }
    }

    public void downloadSnapshot(String s3Key, Path destinationFile) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();
        s3Client.getObject(getRequest, destinationFile);
        logger.info("Downloaded snapshot s3://{}/{} to {}", bucketName, s3Key, destinationFile);
    }

    public void deleteSnapshot(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            return;
        }
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.deleteObject(deleteRequest);
            logger.info("Deleted snapshot s3://{}/{}", bucketName, s3Key);
        } catch (Exception e) {
            logger.warn("Failed deleting snapshot s3://{}/{}: {}", bucketName, s3Key, e.getMessage());
        }
    }

    public void createInstanceFolders(UUID instanceId) {
        if (instanceId == null) {
            return;
        }
        try {
            ensureBackupBucketExists();
            String instanceFolder = "instances/" + instanceId + "/";
            String backupsFolder = "instances/" + instanceId + "/backups/";
            uploadSnapshot(instanceFolder, new byte[0], "application/x-directory");
            uploadSnapshot(backupsFolder, new byte[0], "application/x-directory");
            logger.info("Created folder structure in bucket '{}': {} and {}", bucketName, instanceFolder, backupsFolder);
        } catch (Exception e) {
            logger.warn("Non-fatal error creating instance folders for instance {}: {}", instanceId, e.getMessage());
        }
    }

    public String generateDownloadPresignedUrl(String s3Key, Long durationMinutes) {
        long duration = (durationMinutes != null && durationMinutes > 0) ? durationMinutes : 60;
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(duration))
                .getObjectRequest(getRequest)
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return presigned.url().toString();
    }
}
