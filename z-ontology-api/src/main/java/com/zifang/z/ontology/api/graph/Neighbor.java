package com.zifang.z.ontology.api.graph;

/**
 * 邻居：相邻节点 + 连接边 + 方向（OUT / IN）。
 */
public class Neighbor {

    private GraphNode node;
    private GraphEdge edge;
    private String direction;
    private int depth;

    public Neighbor() {
    }

    public Neighbor(GraphNode node, GraphEdge edge, String direction, int depth) {
        this.node = node;
        this.edge = edge;
        this.direction = direction;
        this.depth = depth;
    }

    public GraphNode getNode() { return node; }
    public void setNode(GraphNode node) { this.node = node; }
    public GraphEdge getEdge() { return edge; }
    public void setEdge(GraphEdge edge) { this.edge = edge; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
}
