package com.zifang.z.ontology.api.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 本体属性（TBox 中的 ObjectProperty / DatatypeProperty）。
 *
 * <p>domain / range 用于推理和校验：语句 (s,p,o) 会推出 s 的类型为 domain，
 * 对象属性的 o 会推出类型为 range。</p>
 */
public class OntologyProperty {

    private String name;
    private PropertyKind kind = PropertyKind.OBJECT;
    private String label;
    private String description;
    private String domain;
    private String range;
    private List<String> superProperties = new ArrayList<>();
    private String inverseOf;
    private boolean transitive;
    private boolean symmetric;
    private boolean functional;

    public OntologyProperty() {
    }

    public OntologyProperty(String name, PropertyKind kind) {
        this.name = name;
        this.kind = kind;
    }

    public OntologyProperty(String name, PropertyKind kind, String domain, String range) {
        this(name, kind);
        this.domain = domain;
        this.range = range;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public PropertyKind getKind() { return kind; }
    public void setKind(PropertyKind kind) { this.kind = kind == null ? PropertyKind.OBJECT : kind; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getRange() { return range; }
    public void setRange(String range) { this.range = range; }
    public List<String> getSuperProperties() { return superProperties; }
    public void setSuperProperties(List<String> superProperties) {
        this.superProperties = superProperties == null ? new ArrayList<>() : new ArrayList<>(superProperties);
    }
    public String getInverseOf() { return inverseOf; }
    public void setInverseOf(String inverseOf) { this.inverseOf = inverseOf; }
    public boolean isTransitive() { return transitive; }
    public void setTransitive(boolean transitive) { this.transitive = transitive; }
    public boolean isSymmetric() { return symmetric; }
    public void setSymmetric(boolean symmetric) { this.symmetric = symmetric; }
    public boolean isFunctional() { return functional; }
    public void setFunctional(boolean functional) { this.functional = functional; }
}
