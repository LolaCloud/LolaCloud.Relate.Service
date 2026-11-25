package com.miranda.luna.relate.infrastructure.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelateStorageServiceTest {

    private S3Client s3Client;
    private S3Presigner s3Presigner;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private RelateStorageService storageService;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        s3Presigner = mock(S3Presigner.class);
        httpClient = mock(HttpClient.class);
        objectMapper = new ObjectMapper();
        storageService = new RelateStorageService(
                s3Client,
                s3Presigner,
                httpClient,
                objectMapper,
                "lola-relate-backups",
                "http://localhost:8086"
        );
    }

    @Test
    void testEnsureBackupBucketExistsWhenBucketAlreadyExists() {
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);

        storageService.ensureBackupBucketExists();

        verify(s3Client, times(1)).headBucket(any(HeadBucketRequest.class));
        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void testEnsureBackupBucketExistsWhenBucketNotFoundRegistersInStore() throws Exception {
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenThrow(NoSuchBucketException.builder().message("Not found").build());

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(201);
        when(response.body()).thenReturn("{\"name\":\"lola-relate-backups\"}");
        org.mockito.Mockito.doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        storageService.ensureBackupBucketExists();

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(1)).send(requestCaptor.capture(), any());
        HttpRequest sentRequest = requestCaptor.getValue();
        assertEquals("http://localhost:8086/api/v1/store/buckets", sentRequest.uri().toString());
        assertEquals("POST", sentRequest.method());
        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void testEnsureBackupBucketExistsFallbackToDirectS3WhenStoreOffline() throws Exception {
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenThrow(NoSuchBucketException.builder().message("Not found").build());
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new RuntimeException("Connection refused"));

        storageService.ensureBackupBucketExists();

        verify(s3Client, times(1)).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void testCreateInstanceFoldersCreatesZeroByteMarkers() {
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);

        UUID instanceId = UUID.randomUUID();
        storageService.createInstanceFolders(instanceId);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(captor.capture(), any(RequestBody.class));

        assertEquals("instances/" + instanceId + "/", captor.getAllValues().get(0).key());
        assertEquals("instances/" + instanceId + "/backups/", captor.getAllValues().get(1).key());
    }

    @Test
    void testUploadSnapshotBytes() {
        byte[] data = "test backup data".getBytes(StandardCharsets.UTF_8);
        String s3Key = "instances/uuid/backups/b1.dump";

        storageService.uploadSnapshot(s3Key, data, "application/octet-stream");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(1)).putObject(captor.capture(), any(RequestBody.class));

        assertEquals("lola-relate-backups", captor.getValue().bucket());
        assertEquals(s3Key, captor.getValue().key());
        assertEquals("application/octet-stream", captor.getValue().contentType());
    }

    @Test
    void testUploadSnapshotFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("snapshot.dump");
        Files.writeString(file, "snapshot binary content");
        String s3Key = "instances/uuid/backups/b2.dump";

        storageService.uploadSnapshot(s3Key, file, "application/octet-stream");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(1)).putObject(captor.capture(), any(RequestBody.class));

        assertEquals("lola-relate-backups", captor.getValue().bucket());
        assertEquals(s3Key, captor.getValue().key());
    }

    @Test
    void testDownloadSnapshot(@TempDir Path tempDir) {
        Path dest = tempDir.resolve("downloaded.dump");
        String s3Key = "instances/uuid/backups/b1.dump";

        storageService.downloadSnapshot(s3Key, dest);

        verify(s3Client, times(1)).getObject(any(GetObjectRequest.class), any(Path.class));
    }

    @Test
    void testDeleteSnapshot() {
        String s3Key = "instances/uuid/backups/b1.dump";
        storageService.deleteSnapshot(s3Key);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(1)).deleteObject(captor.capture());
        assertEquals("lola-relate-backups", captor.getValue().bucket());
        assertEquals(s3Key, captor.getValue().key());
    }

    @Test
    void testGenerateDownloadPresignedUrl() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("http://127.0.0.1:9000/lola-relate-backups/b1.dump?token=abc").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = storageService.generateDownloadPresignedUrl("instances/uuid/backups/b1.dump", 30L);

        assertNotNull(url);
        assertEquals("http://127.0.0.1:9000/lola-relate-backups/b1.dump?token=abc", url);
    }
}
