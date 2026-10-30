package com.zifang.z.ontology.api.graph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图查询视角的边。type 是谓词名或内置关系（SUB_CLASS_OF / INSTANCE_OF）。
 */
public class GraphEdge {

    private String key;
    private String type;
    private String fromKey;
    private String toKey;
    private Map<String, Object> properties = new LinkedHashMap<>();

    public GraphEdge() {
    }

    public GraphEdge(String key, String type, String fromKey, String toKey, Map<String, Object> properties) {
        this.key = key;
        this.type = type;
        this.fromKey = fromKey;
        this.toKey = toKey;
        this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFromKey() { return fromKey; }
    public void setFromKey(String fromKey) { this.fromKey = fromKey; }
    public String getToKey() { return toKey; }
    public void setToKey(String toKey) { this.toKey = toKey; }
    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }
}
