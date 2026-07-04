package com.uagrm.gestion.backend_core.infrastructure.aws;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class S3StorageServiceImpl implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket.name:tramites-gestion-storage}")
    private String bucketName;

    @Override
    public String uploadFile(String clientId, String tramiteId, String fileName, InputStream inputStream,
            String contentType,
            long contentLength) {
        String key = generateKey(clientId, tramiteId, fileName);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, contentLength));
        return key;
    }

    @Override
    public String generatePresignedDownloadUrl(String clientId, String tramiteId, String fileName) {
        String key = generateKey(clientId, tramiteId, fileName);

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(req -> req.bucket(bucketName).key(key))
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    @Override
    public String generatePresignedUploadUrl(String clientId, String tramiteId, String fileName, String contentType) {
        String key = generateKey(clientId, tramiteId, fileName);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(putObjectRequest)
                .build();

        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }

    /**
     * Construye la ruta jerárquica del objeto en S3.
     * Estructura: clients/{clientId}/tramites/{tramiteId}/{fileName}
     */
    private String generateKey(String clientId, String tramiteId, String fileName) {
        return String.format("clients/cliente-%s/tramites/%s/%s", clientId, tramiteId, fileName);
    }
}
