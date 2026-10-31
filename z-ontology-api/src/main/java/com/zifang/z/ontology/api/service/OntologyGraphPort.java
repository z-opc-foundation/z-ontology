package com.zifang.z.ontology.api.service;

import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.graph.Neighbor;
import com.zifang.z.ontology.api.graph.Subgraph;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 物化图端口：本体层只依赖这个接口，z-graph / 其他图后端各自适配。
 *
 * <p>所有 key 由本体层定义（class:xxx / individual:xxx / 边 key），端口负责按
 * ontologyId + workspace 隔离。reset 会清掉该 scope 下上一次物化的全部节点和边。</p>
 */
public interface OntologyGraphPort {

    void reset(String ontologyId, String workspace);

    void upsertNode(String ontologyId, String workspace, String key,
                    Collection<String> labels, Map<String, Object> properties);

    void upsertEdge(String ontologyId, String workspace, String key, String type,
                    String fromKey, String toKey, Map<String, Object> properties);

    GraphNode findNode(String ontologyId, String workspace, String key);

    List<GraphNode> listNodes(String ontologyId, String workspace);

    List<Neighbor> neighbors(String ontologyId, String workspace, String key, int depth, String edgeType);

    Subgraph subgraph(String ontologyId, String workspace, Collection<String> keys, int depth);

    long nodeCount(String ontologyId, String workspace);

    long edgeCount(String ontologyId, String workspace);

    /**
     * 批量原子写能力：begin 之后、commit 之前，该线程对本端口的 reset / upsert
     * 暂不生效；commit 让整批一次性生效，rollback 丢弃整批。
     *
     * <p>物化器用它保证「reset 之后中途失败不留半成品」。默认不支持，
     * 实现类按后端能力覆盖。</p>
     */
    default boolean supportsAtomicBatch() {
        return false;
    }

    default void beginAtomicBatch() {
        throw new UnsupportedOperationException("this port does not support atomic batches");
    }

    default void commitAtomicBatch() {
        throw new UnsupportedOperationException("this port does not support atomic batches");
    }

    default void rollbackAtomicBatch() {
        throw new UnsupportedOperationException("this port does not support atomic batches");
    }
}
