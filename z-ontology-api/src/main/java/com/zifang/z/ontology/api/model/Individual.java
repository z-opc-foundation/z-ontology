package com.zifang.z.ontology.api.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本体个体（ABox）。key 是跨来源稳定的身份，types 是显式类型，inferredTypes 由推理器填充。
 */
public class Individual {

    private String key;
    private String name;
    private String label;
    private Set<String> types = new LinkedHashSet<>();
    private Set<String> inferredTypes = new LinkedHashSet<>();
    private Map<String, Object> properties = new LinkedHashMap<>();
    private List<String> sources = new ArrayList<>();

    public Individual() {
    }

    public Individual(String key, String name) {
        this.key = key;
        this.name = name;
    }

    public Individual addType(String className) {
        if (className != null && !className.isEmpty()) types.add(className);
        return this;
    }

    public Set<String> allTypes() {
        Set<String> all = new LinkedHashSet<>(types);
        all.addAll(inferredTypes);
        return all;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Set<String> getTypes() { return types; }
    public void setTypes(Set<String> types) {
        this.types = types == null ? new LinkedHashSet<>() : new LinkedHashSet<>(types);
    }
    public Set<String> getInferredTypes() { return inferredTypes; }
    public void setInferredTypes(Set<String> inferredTypes) {
        this.inferredTypes = inferredTypes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(inferredTypes);
    }
    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }
    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) {
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
    }
}
