package com.zifang.z.ontology.core.schema;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.ontology.api.exception.OntologyException;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.service.OntologyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 一行一个 JSON 文件的 Schema 存储（默认 ~/.zont/ontologies）。
 *
 * <p>文件名用 URL-safe Base64 编码 id，避免 id 里的 / 与 .. 造成路径穿越。</p>
 */
public class JsonFileOntologyStore implements OntologyStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileOntologyStore.class);

    private final Path directory;

    public JsonFileOntologyStore(Path directory) {
        this.directory = directory;
    }

    public Path getDirectory() {
        return directory;
    }

    @Override
    public synchronized void save(OntologySchema schema) {
        if (schema == null || schema.getId() == null || schema.getId().trim().isEmpty()) {
            throw new IllegalArgumentException("schema.id is required");
        }
        try {
            Files.createDirectories(directory);
            Path target = fileOf(schema.getId());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, JsonUtil.toJsonPretty(schema).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new OntologyException("Failed to save ontology schema " + schema.getId(), e);
        }
    }

    @Override
    public synchronized Optional<OntologySchema> find(String id) {
        if (id == null || id.trim().isEmpty()) return Optional.empty();
        Path file = fileOf(id);
        if (!Files.exists(file)) return Optional.empty();
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            OntologySchema schema = JsonUtil.fromJson(json, OntologySchema.class);
            return Optional.ofNullable(schema);
        } catch (IOException e) {
            throw new OntologyException("Failed to read ontology schema " + id, e);
        }
    }

    @Override
    public synchronized List<OntologySchema> list() {
        List<OntologySchema> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) return result;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path path : stream) files.add(path);
        } catch (IOException e) {
            throw new OntologyException("Failed to list ontology schemas from " + directory, e);
        }
        files.sort(Comparator.comparing(Path::toString));
        for (Path file : files) {
            try {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                OntologySchema schema = JsonUtil.fromJson(json, OntologySchema.class);
                if (schema != null) result.add(schema);
            } catch (IOException | RuntimeException e) {
                log.warn("跳过无法读取的本体文件 {}: {}", file, e.toString());
            }
        }
        return result;
    }

    @Override
    public synchronized boolean delete(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        try {
            return Files.deleteIfExists(fileOf(id));
        } catch (IOException e) {
            throw new OntologyException("Failed to delete ontology schema " + id, e);
        }
    }

    private Path fileOf(String id) {
        String name = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8));
        return directory.resolve(name + ".json");
    }
}
