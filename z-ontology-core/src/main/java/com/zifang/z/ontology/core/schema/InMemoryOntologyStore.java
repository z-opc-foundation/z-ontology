package com.zifang.z.ontology.core.schema;

import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.service.OntologyStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 进程内 Schema 存储，测试与临时运行用。
 */
public class InMemoryOntologyStore implements OntologyStore {

    private final Map<String, OntologySchema> schemas = new LinkedHashMap<>();

    @Override
    public synchronized void save(OntologySchema schema) {
        schemas.put(schema.getId(), schema);
    }

    @Override
    public synchronized Optional<OntologySchema> find(String id) {
        return Optional.ofNullable(schemas.get(id));
    }

    @Override
    public synchronized List<OntologySchema> list() {
        return new ArrayList<>(schemas.values());
    }

    @Override
    public synchronized boolean delete(String id) {
        return schemas.remove(id) != null;
    }
}
