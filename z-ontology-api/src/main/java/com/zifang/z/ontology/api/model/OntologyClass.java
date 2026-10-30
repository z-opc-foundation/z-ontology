package com.zifang.z.ontology.api.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 本体类（TBox 中的 Class）。名称是本体内唯一标识，父类构成子类关系。
 */
public class OntologyClass {

    private String name;
    private String label;
    private String description;
    private List<String> superClasses = new ArrayList<>();
    private List<String> disjointWith = new ArrayList<>();

    public OntologyClass() {
    }

    public OntologyClass(String name) {
        this.name = name;
    }

    public OntologyClass(String name, String label, List<String> superClasses) {
        this.name = name;
        this.label = label;
        this.superClasses = superClasses == null ? new ArrayList<>() : new ArrayList<>(superClasses);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getSuperClasses() { return superClasses; }
    public void setSuperClasses(List<String> superClasses) {
        this.superClasses = superClasses == null ? new ArrayList<>() : new ArrayList<>(superClasses);
    }
    public List<String> getDisjointWith() { return disjointWith; }
    public void setDisjointWith(List<String> disjointWith) {
        this.disjointWith = disjointWith == null ? new ArrayList<>() : new ArrayList<>(disjointWith);
    }
}
