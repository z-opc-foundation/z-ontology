package com.zifang.z.ontology.api.model;

import java.time.Instant;

/**
 * 一次物化的结果读数。
 */
public class MaterializationReport {

    private String ontologyId;
    private String workspace;
    private String sourceId;
    private int classNodes;
    private int individualNodes;
    private int edges;
    private int explicitStatements;
    private int inferredStatements;
    private long durationMs;
    private Instant materializedAt;
    private ValidationReport validation;

    public String getOntologyId() { return ontologyId; }
    public void setOntologyId(String ontologyId) { this.ontologyId = ontologyId; }
    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public int getClassNodes() { return classNodes; }
    public void setClassNodes(int classNodes) { this.classNodes = classNodes; }
    public int getIndividualNodes() { return individualNodes; }
    public void setIndividualNodes(int individualNodes) { this.individualNodes = individualNodes; }
    public int getEdges() { return edges; }
    public void setEdges(int edges) { this.edges = edges; }
    public int getExplicitStatements() { return explicitStatements; }
    public void setExplicitStatements(int explicitStatements) { this.explicitStatements = explicitStatements; }
    public int getInferredStatements() { return inferredStatements; }
    public void setInferredStatements(int inferredStatements) { this.inferredStatements = inferredStatements; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Instant getMaterializedAt() { return materializedAt; }
    public void setMaterializedAt(Instant materializedAt) { this.materializedAt = materializedAt; }
    public ValidationReport getValidation() { return validation; }
    public void setValidation(ValidationReport validation) { this.validation = validation; }
}
