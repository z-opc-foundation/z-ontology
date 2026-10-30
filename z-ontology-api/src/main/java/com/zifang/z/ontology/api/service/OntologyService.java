package com.zifang.z.ontology.api.service;

import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.graph.Neighbor;
import com.zifang.z.ontology.api.graph.Subgraph;
import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.MaterializationReport;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.OntologyStats;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.api.model.ValidationReport;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 本体服务门面：Schema 管理 / 推理 / 校验 / 物化 / 图查询。
 */
public interface OntologyService {

    OntologySchema registerSchema(OntologySchema schema);

    Optional<OntologySchema> findSchema(String ontologyId);

    List<OntologySchema> listSchemas();

    boolean deleteSchema(String ontologyId);

    InferenceResult infer(String ontologyId, List<Individual> individuals, List<Statement> statements);

    ValidationReport validate(String ontologyId, List<Individual> individuals, List<Statement> statements);

    ValidationReport validateSource(String ontologyId, String workspace, String sourceId);

    MaterializationReport materialize(String ontologyId, String workspace, String sourceId);

    MaterializationReport materializeData(String ontologyId, String workspace,
                                          List<Individual> individuals, List<Statement> statements);

    List<OntologySource> sources();

    void registerSource(OntologySource source);

    List<GraphNode> classNodes(String ontologyId, String workspace);

    List<GraphNode> individualNodes(String ontologyId, String workspace);

    List<GraphNode> instances(String ontologyId, String workspace, String className);

    List<Neighbor> neighbors(String ontologyId, String workspace, String key, int depth, String edgeType);

    Subgraph subgraph(String ontologyId, String workspace, Collection<String> keys, int depth);

    OntologyStats stats(String ontologyId, String workspace);
}
