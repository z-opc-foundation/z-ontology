package com.zifang.z.ontology.api.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个本体：类集合 + 属性集合，可带版本与命名空间前缀。
 */
public class OntologySchema {

    private String id;
    private String name;
    private String version = "1.0.0";
    private String description;
    private Map<String, String> prefixes = new LinkedHashMap<>();
    private List<OntologyClass> classes = new ArrayList<>();
    private List<OntologyProperty> properties = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public OntologySchema() {
    }

    public OntologySchema(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public OntologyClass findClass(String className) {
        if (className == null) return null;
        for (OntologyClass c : classes) {
            if (className.equals(c.getName())) return c;
        }
        return null;
    }

    public OntologyProperty findProperty(String propertyName) {
        if (propertyName == null) return null;
        for (OntologyProperty p : properties) {
            if (propertyName.equals(p.getName())) return p;
        }
        return null;
    }

    public Set<String> classNames() {
        Set<String> names = new LinkedHashSet<>();
        for (OntologyClass c : classes) names.add(c.getName());
        return names;
    }

    public Set<String> propertyNames() {
        Set<String> names = new LinkedHashSet<>();
        for (OntologyProperty p : properties) names.add(p.getName());
        return names;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, String> getPrefixes() { return prefixes; }
    public void setPrefixes(Map<String, String> prefixes) {
        this.prefixes = prefixes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prefixes);
    }
    public List<OntologyClass> getClasses() { return classes; }
    public void setClasses(List<OntologyClass> classes) {
        this.classes = classes == null ? new ArrayList<>() : new ArrayList<>(classes);
    }
    public List<OntologyProperty> getProperties() { return properties; }
    public void setProperties(List<OntologyProperty> properties) {
        this.properties = properties == null ? new ArrayList<>() : new ArrayList<>(properties);
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
