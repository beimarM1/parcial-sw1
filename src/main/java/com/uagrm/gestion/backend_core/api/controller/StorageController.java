package com.uagrm.gestion.backend_core.api.controller;

import com.uagrm.gestion.backend_core.api.dto.PresignedUrlResponse;
import com.uagrm.gestion.backend_core.api.dto.UploadResponse;
import com.uagrm.gestion.backend_core.domain.model.Tramite;
import com.uagrm.gestion.backend_core.infrastructure.aws.StorageService;
import com.uagrm.gestion.backend_core.service.TramiteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
@Tag(name = "Almacenamiento S3", description = "Endpoints para la gestión de archivos en AWS S3")
public class StorageController {

    private final StorageService storageService;
    private final TramiteService tramiteService;

    // ─────────────────────────────────────────────────────────────────────────
    // Método utilitario: resuelve el clientId real desde la BD a partir del tramiteId.
    // Si el trámite no existe o no tiene usuario asignado, devuelve "cliente-desconocido".
    // ─────────────────────────────────────────────────────────────────────────
    private String resolverClientId(String tramiteId) {
        try {
            // Limpiar el prefijo "tramite-" que envía el frontend (ej: "tramite-abc123" → "abc123")
            String cleanTramiteId = tramiteId != null
                    ? tramiteId.replace("tramite-", "")
                    : "";

            return tramiteService.obtenerPorId(cleanTramiteId)
                    .map(t -> {
                        // idUsuarioFinal es quien inició el trámite (el cliente externo / Flutter)
                        String clientId = t.getIdUsuarioFinal();
                        if (clientId == null || clientId.isBlank()) {
                            // Fallback: usar el workflowId como agrupador si no hay usuario asignado
                            clientId = t.getWorkflowDefinitionId() != null
                                    ? "wf-" + t.getWorkflowDefinitionId()
                                    : "cliente-desconocido";
                        }
                        return clientId;
                    })
                    .orElseGet(() -> {
                        log.warn("[StorageController] Trámite '{}' no encontrado en BD. Usando clientId='cliente-desconocido'", tramiteId);
                        return "cliente-desconocido";
                    });
        } catch (Exception e) {
            log.error("[StorageController] Error al resolver clientId para tramiteId '{}': {}", tramiteId, e.getMessage());
            return "cliente-desconocido";
        }
    }

    /**
     * Subida directa de archivo desde el backend (Flujo B - Bypass Multipart).
     * Angular/Flutter → Backend → S3
     * Ruta final en S3: clients/{clientId}/tramites/{tramiteId}/{fileName}
     */
    @PostMapping(value = "/upload/{tramiteId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('USUARIO_FINAL', 'FUNCIONARIO', 'DISEÑADOR_POLITICAS')")
    @Operation(summary = "Subir archivo al S3 agrupado por cliente y trámite")
    public ResponseEntity<UploadResponse> uploadFile(
            @PathVariable String tramiteId,
            @RequestParam("file") MultipartFile file) throws IOException {

        // 1. Resolver el clientId real desde la base de datos
        String clientId = resolverClientId(tramiteId);
        log.info("[StorageController] uploadFile | tramiteId={} | clientId resuelto={}", tramiteId, clientId);

        // 2. Sanitizar el nombre del archivo
        String cleanFileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9.]", "_")
                : "archivo_sin_nombre";

        // 3. Delegar la subida real al servicio con la ruta jerárquica correcta
        String key = storageService.uploadFile(
                clientId,
                tramiteId,
                cleanFileName,
                file.getInputStream(),
                file.getContentType(),
                file.getSize());

        return ResponseEntity.ok(UploadResponse.builder()
                .key(key)
                .success(true)
                .message("Archivo subido correctamente.")
                .build());
    }

    /**
     * Genera una URL prefirmada para DESCARGA directa desde S3.
     * El cliente descarga el archivo directamente sin pasar por el backend.
     * Ruta final en S3: clients/{clientId}/tramites/{tramiteId}/{fileName}
     */
    @GetMapping("/download-url/{tramiteId}")
    @PreAuthorize("hasAnyRole('USUARIO_FINAL', 'FUNCIONARIO', 'DISEÑADOR_POLITICAS')")
    @Operation(summary = "Generar Pre-signed URL para descarga directa desde S3")
    public ResponseEntity<PresignedUrlResponse> getPresignedDownloadUrl(
            @PathVariable String tramiteId,
            @RequestParam String fileName) {

        // 1. Resolver el clientId real desde la base de datos
        String clientId = resolverClientId(tramiteId);
        log.info("[StorageController] getPresignedDownloadUrl | tramiteId={} | clientId resuelto={}", tramiteId, clientId);

        // 2. Generar la URL prefirmada con la ruta jerárquica correcta
        String url = storageService.generatePresignedDownloadUrl(clientId, tramiteId, fileName);
        String s3Key = "clients/" + clientId + "/tramites/" + tramiteId + "/" + fileName;

        return ResponseEntity.ok(PresignedUrlResponse.builder()
                .url(url)
                .key(s3Key)
                .expiresInMinutes(15)
                .build());
    }

    /**
     * Genera una URL prefirmada para SUBIDA directa desde Angular/Flutter → S3 (Flujo A).
     * Evita que el archivo pase por el backend: reduce latencia y costo de ancho de banda.
     * Ruta final en S3: clients/{clientId}/tramites/{tramiteId}/{fileName}
     */
    @GetMapping("/upload-url/{tramiteId}")
    @PreAuthorize("hasAnyRole('USUARIO_FINAL', 'FUNCIONARIO', 'DISEÑADOR_POLITICAS')")
    @Operation(summary = "Generar Pre-signed URL para subida directa al S3 desde el cliente")
    public ResponseEntity<PresignedUrlResponse> getPresignedUploadUrl(
            @PathVariable String tramiteId,
            @RequestParam String fileName,
            @RequestParam String contentType) {

        // 1. Resolver el clientId real desde la base de datos
        String clientId = resolverClientId(tramiteId);
        log.info("[StorageController] getPresignedUploadUrl | tramiteId={} | clientId resuelto={}", tramiteId, clientId);

        // 2. Sanitizar el nombre del archivo
        String cleanFileName = fileName.replaceAll("[^a-zA-Z0-9.]", "_");

        // 3. Generar la URL prefirmada con la ruta jerárquica correcta
        String url = storageService.generatePresignedUploadUrl(clientId, tramiteId, cleanFileName, contentType);
        String s3Key = "clients/" + clientId + "/tramites/" + tramiteId + "/" + cleanFileName;

        return ResponseEntity.ok(PresignedUrlResponse.builder()
                .url(url)
                .key(s3Key)
                .expiresInMinutes(15)
                .build());
    }
}
