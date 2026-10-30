package com.zifang.z.ontology.api.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理结果：个体（含推断类型）+ 语句（显式 + 推断）。
 */
public class InferenceResult {

    private List<Individual> individuals = new ArrayList<>();
    private List<Statement> statements = new ArrayList<>();
    private int explicitStatements;
    private int inferredStatements;
    private int explicitTypes;
    private int inferredTypes;

    public List<Individual> getIndividuals() { return individuals; }
    public void setIndividuals(List<Individual> individuals) {
        this.individuals = individuals == null ? new ArrayList<>() : individuals;
    }
    public List<Statement> getStatements() { return statements; }
    public void setStatements(List<Statement> statements) {
        this.statements = statements == null ? new ArrayList<>() : statements;
    }
    public int getExplicitStatements() { return explicitStatements; }
    public void setExplicitStatements(int explicitStatements) { this.explicitStatements = explicitStatements; }
    public int getInferredStatements() { return inferredStatements; }
    public void setInferredStatements(int inferredStatements) { this.inferredStatements = inferredStatements; }
    public int getExplicitTypes() { return explicitTypes; }
    public void setExplicitTypes(int explicitTypes) { this.explicitTypes = explicitTypes; }
    public int getInferredTypes() { return inferredTypes; }
    public void setInferredTypes(int inferredTypes) { this.inferredTypes = inferredTypes; }
}
