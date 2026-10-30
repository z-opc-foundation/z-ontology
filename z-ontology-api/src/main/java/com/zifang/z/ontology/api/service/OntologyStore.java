package com.zifang.z.ontology.api.service;

import com.zifang.z.ontology.api.model.OntologySchema;

import java.util.List;
import java.util.Optional;

/**
 * 本体 Schema 存储。
 */
public interface OntologyStore {

    void save(OntologySchema schema);

    Optional<OntologySchema> find(String id);

    List<OntologySchema> list();

    boolean delete(String id);
}
