package com.zifang.z.ontology.api.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 子图：节点与边。
 */
public class Subgraph {

    private List<GraphNode> nodes = new ArrayList<>();
    private List<GraphEdge> edges = new ArrayList<>();

    public Subgraph() {
    }

    public Subgraph(List<GraphNode> nodes, List<GraphEdge> edges) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
        this.edges = edges == null ? new ArrayList<>() : edges;
    }

    public List<GraphNode> getNodes() { return nodes; }
    public void setNodes(List<GraphNode> nodes) { this.nodes = nodes == null ? new ArrayList<>() : nodes; }
    public List<GraphEdge> getEdges() { return edges; }
    public void setEdges(List<GraphEdge> edges) { this.edges = edges == null ? new ArrayList<>() : edges; }
}
