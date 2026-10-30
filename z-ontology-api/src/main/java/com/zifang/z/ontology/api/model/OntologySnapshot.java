package com.zifang.z.ontology.api.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 待推理/物化的一批数据（来自 z-kb、外部导入或手工构造）。
 */
public class OntologySnapshot {

    private String workspace = "default";
    private List<Individual> individuals = new ArrayList<>();
    private List<Statement> statements = new ArrayList<>();

    public OntologySnapshot() {
    }

    public OntologySnapshot(String workspace, List<Individual> individuals, List<Statement> statements) {
        this.workspace = workspace;
        this.individuals = individuals == null ? new ArrayList<>() : individuals;
        this.statements = statements == null ? new ArrayList<>() : statements;
    }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public List<Individual> getIndividuals() { return individuals; }
    public void setIndividuals(List<Individual> individuals) {
        this.individuals = individuals == null ? new ArrayList<>() : individuals;
    }
    public List<Statement> getStatements() { return statements; }
    public void setStatements(List<Statement> statements) {
        this.statements = statements == null ? new ArrayList<>() : statements;
    }
}
