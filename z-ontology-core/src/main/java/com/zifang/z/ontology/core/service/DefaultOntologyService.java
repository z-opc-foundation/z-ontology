package com.zifang.z.ontology.core.service;

import com.zifang.z.ontology.api.exception.InvalidOntologySchemaException;
import com.zifang.z.ontology.api.exception.OntologyException;
import com.zifang.z.ontology.api.exception.OntologyNotFoundException;
import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.graph.Neighbor;
import com.zifang.z.ontology.api.graph.Subgraph;
import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.MaterializationReport;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.OntologySnapshot;
import com.zifang.z.ontology.api.model.OntologyStats;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.api.service.OntologyGraphPort;
import com.zifang.z.ontology.api.service.OntologyService;
import com.zifang.z.ontology.api.service.OntologySource;
import com.zifang.z.ontology.api.service.OntologyStore;
import com.zifang.z.ontology.core.materialize.OntologyMaterializer;
import com.zifang.z.ontology.core.reasoning.RdfsReasoner;
import com.zifang.z.ontology.core.schema.OntologySchemaValidator;
import com.zifang.z.ontology.core.validation.OntologyValidator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认实现：Schema 校验 + 存储、按来源/内联数据推理校验物化、图查询。
 */
public class DefaultOntologyService implements OntologyService {

    private static final int MAX_QUERY_DEPTH = 10;

    private final OntologyStore store;
    private final RdfsReasoner reasoner;
    private final OntologyValidator validator;
    private final OntologyMaterializer materializer;
    private final OntologyGraphPort graphPort;
    private final OntologySchemaValidator schemaValidator = new OntologySchemaValidator();
    private final List<OntologySource> sources = new CopyOnWriteArrayList<>();
    private final Map<String, MaterializationReport> lastReports = new LinkedHashMap<>();

    public DefaultOntologyService(OntologyStore store, RdfsReasoner reasoner, OntologyValidator validator,
                                  OntologyMaterializer materializer, OntologyGraphPort graphPort) {
        this(store, reasoner, validator, materializer, graphPort, Collections.emptyList());
    }

    public DefaultOntologyService(OntologyStore store, RdfsReasoner reasoner, OntologyValidator validator,
                                  OntologyMaterializer materializer, OntologyGraphPort graphPort,
                                  List<OntologySource> sources) {
        this.store = store;
        this.reasoner = reasoner;
        this.validator = validator;
        this.materializer = materializer;
        this.graphPort = graphPort;
        if (sources != null) {
            for (OntologySource source : sources) registerSource(source);
        }
    }

    @Override
    public synchronized OntologySchema registerSchema(OntologySchema schema) {
        ValidationReport report = schemaValidator.validate(schema);
        if (OntologySchemaValidator.hasErrors(report)) {
            throw new InvalidOntologySchemaException("Invalid ontology schema: " + schema.getId(), report);
        }
        Instant now = Instant.now();
        if (schema.getCreatedAt() == null) schema.setCreatedAt(now);
        schema.setUpdatedAt(now);
        store.save(schema);
        return schema;
    }

    @Override
    public Optional<OntologySchema> findSchema(String ontologyId) {
        return store.find(ontologyId);
    }

    @Override
    public List<OntologySchema> listSchemas() {
        return store.list();
    }

    @Override
    public synchronized boolean deleteSchema(String ontologyId) {
        return store.delete(ontologyId);
    }

    @Override
    public InferenceResult infer(String ontologyId, List<Individual> individuals, List<Statement> statements) {
        return reasoner.infer(requireSchema(ontologyId), individuals, statements);
    }

    @Override
    public ValidationReport validate(String ontologyId, List<Individual> individuals, List<Statement> statements) {
        OntologySchema schema = requireSchema(ontologyId);
        InferenceResult inference = reasoner.infer(schema, individuals, statements);
        return validator.validate(schema, inference);
    }

    @Override
    public ValidationReport validateSource(String ontologyId, String workspace, String sourceId) {
        OntologySchema schema = requireSchema(ontologyId);
        OntologySnapshot snapshot = load(sourceId, workspace);
        InferenceResult inference = reasoner.infer(schema, snapshot.getIndividuals(), snapshot.getStatements());
        return validator.validate(schema, inference);
    }

    @Override
    public MaterializationReport materialize(String ontologyId, String workspace, String sourceId) {
        OntologySnapshot snapshot = load(sourceId, workspace);
        return doMaterialize(ontologyId, workspace, sourceId, snapshot.getIndividuals(), snapshot.getStatements());
    }

    @Override
    public MaterializationReport materializeData(String ontologyId, String workspace,
                                                 List<Individual> individuals, List<Statement> statements) {
        return doMaterialize(ontologyId, workspace, "inline", individuals, statements);
    }

    private synchronized MaterializationReport doMaterialize(String ontologyId, String workspace, String sourceId,
                                                             List<Individual> individuals, List<Statement> statements) {
        OntologySchema schema = requireSchema(ontologyId);
        InferenceResult inference = reasoner.infer(schema, individuals, statements);
        ValidationReport validation = validator.validate(schema, inference);
        MaterializationReport report = materializer.materialize(schema, workspace, sourceId,
                inference, validation, graphPort);
        lastReports.put(scope(ontologyId, workspace), report);
        return report;
    }

    @Override
    public List<OntologySource> sources() {
        return Collections.unmodifiableList(new ArrayList<>(sources));
    }

    @Override
    public void registerSource(OntologySource source) {
        if (source == null || source.id() == null || source.id().trim().isEmpty()) {
            throw new IllegalArgumentException("ontology source id is required");
        }
        sources.removeIf(existing -> source.id().equals(existing.id()));
        sources.add(source);
    }

    @Override
    public List<GraphNode> classNodes(String ontologyId, String workspace) {
        requireSchema(ontologyId);
        return filterNodes(ontologyId, workspace, "OntologyClass");
    }

    @Override
    public List<GraphNode> individualNodes(String ontologyId, String workspace) {
        requireSchema(ontologyId);
        return filterNodes(ontologyId, workspace, "OntologyIndividual");
    }

    @Override
    public List<GraphNode> instances(String ontologyId, String workspace, String className) {
        requireSchema(ontologyId);
        List<GraphNode> result = new ArrayList<>();
        for (GraphNode node : filterNodes(ontologyId, workspace, "OntologyIndividual")) {
            Object allTypes = node.getProperties().get("allTypes");
            if (allTypes instanceof Collection && ((Collection<?>) allTypes).contains(className)) {
                result.add(node);
            }
        }
        return result;
    }

    @Override
    public List<Neighbor> neighbors(String ontologyId, String workspace, String key, int depth, String edgeType) {
        requireSchema(ontologyId);
        int safeDepth = Math.max(0, Math.min(depth, MAX_QUERY_DEPTH));
        String safeEdge = edgeType == null || edgeType.trim().isEmpty() ? null : edgeType;
        return graphPort.neighbors(ontologyId, workspace, key, safeDepth, safeEdge);
    }

    @Override
    public Subgraph subgraph(String ontologyId, String workspace, Collection<String> keys, int depth) {
        requireSchema(ontologyId);
        if (keys == null || keys.isEmpty()) {
            return new Subgraph();
        }
        int safeDepth = Math.max(0, Math.min(depth, MAX_QUERY_DEPTH));
        return graphPort.subgraph(ontologyId, workspace, keys, safeDepth);
    }

    @Override
    public OntologyStats stats(String ontologyId, String workspace) {
        requireSchema(ontologyId);
        OntologyStats stats = new OntologyStats();
        stats.setOntologyId(ontologyId);
        stats.setWorkspace(workspace);
        stats.setClassNodes(filterNodes(ontologyId, workspace, "OntologyClass").size());
        stats.setIndividualNodes(filterNodes(ontologyId, workspace, "OntologyIndividual").size());
        stats.setNodeCount(graphPort.nodeCount(ontologyId, workspace));
        stats.setEdgeCount(graphPort.edgeCount(ontologyId, workspace));
        MaterializationReport report = lastReports.get(scope(ontologyId, workspace));
        if (report != null) {
            stats.setExplicitStatements(report.getExplicitStatements());
            stats.setInferredStatements(report.getInferredStatements());
            stats.setLastMaterializedAt(report.getMaterializedAt());
        }
        return stats;
    }

    private List<GraphNode> filterNodes(String ontologyId, String workspace, String label) {
        List<GraphNode> result = new ArrayList<>();
        for (GraphNode node : graphPort.listNodes(ontologyId, workspace)) {
            if (node.getLabels() != null && node.getLabels().contains(label)) result.add(node);
        }
        return result;
    }

    private OntologySchema requireSchema(String ontologyId) {
        return store.find(ontologyId).orElseThrow(() -> new OntologyNotFoundException(ontologyId));
    }

    private OntologySnapshot load(String sourceId, String workspace) {
        for (OntologySource source : sources) {
            if (source.id().equals(sourceId)) {
                OntologySnapshot snapshot = source.load(workspace);
                if (snapshot == null) return new OntologySnapshot(workspace, new ArrayList<>(), new ArrayList<>());
                return snapshot;
            }
        }
        List<String> available = new ArrayList<>();
        for (OntologySource source : sources) available.add(source.id());
        throw new OntologyException("Unknown ontology source: " + sourceId + " (available=" + available + ")");
    }

    private static String scope(String ontologyId, String workspace) {
        return ontologyId + "\u0000" + workspace;
    }
}
