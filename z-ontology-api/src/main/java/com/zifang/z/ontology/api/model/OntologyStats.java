package com.zifang.z.ontology.api.model;

import java.time.Instant;

/**
 * 某个本体在某个 workspace 下的物化读数。
 */
public class OntologyStats {

    private String ontologyId;
    private String workspace;
    private int classNodes;
    private int individualNodes;
    private long nodeCount;
    private long edgeCount;
    private int explicitStatements;
    private int inferredStatements;
    private Instant lastMaterializedAt;

    public String getOntologyId() { return ontologyId; }
    public void setOntologyId(String ontologyId) { this.ontologyId = ontologyId; }
    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public int getClassNodes() { return classNodes; }
    public void setClassNodes(int classNodes) { this.classNodes = classNodes; }
    public int getIndividualNodes() { return individualNodes; }
    public void setIndividualNodes(int individualNodes) { this.individualNodes = individualNodes; }
    public long getNodeCount() { return nodeCount; }
    public void setNodeCount(long nodeCount) { this.nodeCount = nodeCount; }
    public long getEdgeCount() { return edgeCount; }
    public void setEdgeCount(long edgeCount) { this.edgeCount = edgeCount; }
    public int getExplicitStatements() { return explicitStatements; }
    public void setExplicitStatements(int explicitStatements) { this.explicitStatements = explicitStatements; }
    public int getInferredStatements() { return inferredStatements; }
    public void setInferredStatements(int inferredStatements) { this.inferredStatements = inferredStatements; }
    public Instant getLastMaterializedAt() { return lastMaterializedAt; }
    public void setLastMaterializedAt(Instant lastMaterializedAt) { this.lastMaterializedAt = lastMaterializedAt; }
}
