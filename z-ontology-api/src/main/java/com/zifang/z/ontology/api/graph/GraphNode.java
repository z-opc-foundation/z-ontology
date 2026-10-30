package com.zifang.z.ontology.api.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图查询视角的节点。labels 是本体层标签（OntologyClass / OntologyIndividual / 类名）。
 */
public class GraphNode {

    private String key;
    private List<String> labels = new ArrayList<>();
    private Map<String, Object> properties = new LinkedHashMap<>();

    public GraphNode() {
    }

    public GraphNode(String key, List<String> labels, Map<String, Object> properties) {
        this.key = key;
        this.labels = labels == null ? new ArrayList<>() : new ArrayList<>(labels);
        this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels == null ? new ArrayList<>() : new ArrayList<>(labels); }
    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }
}
