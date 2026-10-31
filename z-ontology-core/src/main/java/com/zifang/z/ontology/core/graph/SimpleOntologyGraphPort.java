package com.zifang.z.ontology.core.graph;

import com.zifang.z.ontology.api.graph.GraphEdge;
import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.graph.Neighbor;
import com.zifang.z.ontology.api.graph.Subgraph;
import com.zifang.z.ontology.api.service.OntologyGraphPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯内存的物化图端口，作为没有 z-graph 时的兜底（测试里也直接用它）。
 *
 * <p>语义与 z-graph 适配器一致：按 ontologyId + workspace 隔离，reset 只清本 scope。
 * 需要持久化或共享图时换成 {@code z-ontology-graph} 提供的端口实现。</p>
 */
public class SimpleOntologyGraphPort implements OntologyGraphPort {

    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
    private List<PendingOp> batch;

    @Override
    public boolean supportsAtomicBatch() {
        return true;
    }

    @Override
    public synchronized void beginAtomicBatch() {
        if (batch != null) {
            throw new IllegalStateException("atomic batch already active");
        }
        batch = new ArrayList<>();
    }

    @Override
    public synchronized void commitAtomicBatch() {
        List<PendingOp> ops = batch;
        batch = null;
        if (ops == null) {
            return;
        }
        for (PendingOp op : ops) {
            switch (op.kind) {
                case PendingOp.RESET:
                    doReset(op.ontologyId, op.workspace);
                    break;
                case PendingOp.NODE:
                    doUpsertNode(op.ontologyId, op.workspace, op.key, op.labels, op.properties);
                    break;
                case PendingOp.EDGE:
                    doUpsertEdge(op.ontologyId, op.workspace, op.key, op.type,
                            op.fromKey, op.toKey, op.properties);
                    break;
                default:
                    throw new IllegalStateException("unknown pending op: " + op.kind);
            }
        }
    }

    @Override
    public synchronized void rollbackAtomicBatch() {
        batch = null;
    }

    @Override
    public synchronized void reset(String ontologyId, String workspace) {
        if (batch != null) {
            batch.add(PendingOp.reset(ontologyId, workspace));
            return;
        }
        doReset(ontologyId, workspace);
    }

    private void doReset(String ontologyId, String workspace) {
        scopes.remove(scopeKey(ontologyId, workspace));
    }

    @Override
    public synchronized void upsertNode(String ontologyId, String workspace, String key,
                                        Collection<String> labels, Map<String, Object> properties) {
        if (batch != null) {
            batch.add(PendingOp.node(ontologyId, workspace, key, labels, properties));
            return;
        }
        doUpsertNode(ontologyId, workspace, key, labels, properties);
    }

    private void doUpsertNode(String ontologyId, String workspace, String key,
                              Collection<String> labels, Map<String, Object> properties) {
        Scope scope = scopeOf(ontologyId, workspace);
        GraphNode existing = scope.nodes.get(key);
        if (existing == null) {
            scope.nodes.put(key, new GraphNode(key, labels == null ? null : new ArrayList<>(labels), properties));
            return;
        }
        if (labels != null) {
            existing.setLabels(new ArrayList<>(labels));
        }
        if (properties != null) {
            existing.getProperties().putAll(properties);
        }
    }

    @Override
    public synchronized void upsertEdge(String ontologyId, String workspace, String key, String type,
                                        String fromKey, String toKey, Map<String, Object> properties) {
        if (batch != null) {
            batch.add(PendingOp.edge(ontologyId, workspace, key, type, fromKey, toKey, properties));
            return;
        }
        doUpsertEdge(ontologyId, workspace, key, type, fromKey, toKey, properties);
    }

    private void doUpsertEdge(String ontologyId, String workspace, String key, String type,
                              String fromKey, String toKey, Map<String, Object> properties) {
        Scope scope = scopeOf(ontologyId, workspace);
        GraphEdge existing = scope.edges.get(key);
        if (existing != null) {
            if (properties != null) {
                existing.getProperties().putAll(properties);
            }
            return;
        }
        scope.edges.put(key, new GraphEdge(key, type, fromKey, toKey, properties));
        scope.outEdges.computeIfAbsent(fromKey, k -> new LinkedHashSet<>()).add(key);
        scope.inEdges.computeIfAbsent(toKey, k -> new LinkedHashSet<>()).add(key);
    }

    @Override
    public GraphNode findNode(String ontologyId, String workspace, String key) {
        Scope scope = scopes.get(scopeKey(ontologyId, workspace));
        return scope == null ? null : copy(scope.nodes.get(key));
    }

    @Override
    public List<GraphNode> listNodes(String ontologyId, String workspace) {
        Scope scope = scopes.get(scopeKey(ontologyId, workspace));
        if (scope == null) {
            return Collections.emptyList();
        }
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : scope.nodes.values()) {
            nodes.add(copy(node));
        }
        nodes.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return nodes;
    }

    @Override
    public List<Neighbor> neighbors(String ontologyId, String workspace, String key, int depth, String edgeType) {
        Scope scope = scopes.get(scopeKey(ontologyId, workspace));
        if (scope == null || depth <= 0 || !scope.nodes.containsKey(key)) {
            return Collections.emptyList();
        }
        List<Neighbor> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> seenEdges = new LinkedHashSet<>();
        visited.add(key);
        Deque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[]{key, "0"});
        while (!queue.isEmpty()) {
            String[] current = queue.poll();
            int currentDepth = Integer.parseInt(current[1]);
            if (currentDepth >= depth) {
                continue;
            }
            for (String edgeKey : scope.outEdges.getOrDefault(current[0], Collections.emptySet())) {
                GraphEdge edge = scope.edges.get(edgeKey);
                if (edge == null || (edgeType != null && !edgeType.equals(edge.getType()))) {
                    continue;
                }
                reportEdge(scope, current[0], edge, edge.getToKey(), "OUT", currentDepth + 1,
                        visited, seenEdges, queue, result);
            }
            for (String edgeKey : scope.inEdges.getOrDefault(current[0], Collections.emptySet())) {
                GraphEdge edge = scope.edges.get(edgeKey);
                if (edge == null || (edgeType != null && !edgeType.equals(edge.getType()))) {
                    continue;
                }
                reportEdge(scope, current[0], edge, edge.getFromKey(), "IN", currentDepth + 1,
                        visited, seenEdges, queue, result);
            }
        }
        return result;
    }

    /** 每条符合 edgeType 的边恰好报告一次（平行边全部返回）；节点只入队一次；自环边跳过。 */
    private void reportEdge(Scope scope, String currentKey, GraphEdge edge, String neighborKey, String direction,
                            int depth, Set<String> visited, Set<String> seenEdges,
                            Deque<String[]> queue, List<Neighbor> result) {
        if (currentKey.equals(neighborKey) || !seenEdges.add(edge.getKey())) {
            return;
        }
        result.add(new Neighbor(copy(scope.nodes.get(neighborKey)), copy(edge), direction, depth));
        if (visited.add(neighborKey)) {
            queue.add(new String[]{neighborKey, String.valueOf(depth)});
        }
    }

    @Override
    public Subgraph subgraph(String ontologyId, String workspace, Collection<String> keys, int depth) {
        Scope scope = scopes.get(scopeKey(ontologyId, workspace));
        if (scope == null || keys == null || keys.isEmpty()) {
            return new Subgraph();
        }
        Set<String> visited = new LinkedHashSet<>();
        Deque<String[]> queue = new ArrayDeque<>();
        for (String key : keys) {
            if (key != null && scope.nodes.containsKey(key)) {
                visited.add(key);
                queue.add(new String[]{key, "0"});
            }
        }
        int safeDepth = Math.max(0, depth);
        while (!queue.isEmpty()) {
            String[] current = queue.poll();
            int currentDepth = Integer.parseInt(current[1]);
            if (currentDepth >= safeDepth) {
                continue;
            }
            for (String edgeKey : scope.outEdges.getOrDefault(current[0], Collections.emptySet())) {
                GraphEdge edge = scope.edges.get(edgeKey);
                if (edge != null && visited.add(edge.getToKey())) {
                    queue.add(new String[]{edge.getToKey(), String.valueOf(currentDepth + 1)});
                }
            }
            for (String edgeKey : scope.inEdges.getOrDefault(current[0], Collections.emptySet())) {
                GraphEdge edge = scope.edges.get(edgeKey);
                if (edge != null && visited.add(edge.getFromKey())) {
                    queue.add(new String[]{edge.getFromKey(), String.valueOf(currentDepth + 1)});
                }
            }
        }
        List<GraphNode> nodes = new ArrayList<>();
        for (String key : visited) {
            nodes.add(copy(scope.nodes.get(key)));
        }
        nodes.sort((a, b) -> a.getKey().compareTo(b.getKey()));

        Set<String> edgeKeys = new LinkedHashSet<>();
        for (String key : visited) {
            edgeKeys.addAll(scope.outEdges.getOrDefault(key, Collections.emptySet()));
            edgeKeys.addAll(scope.inEdges.getOrDefault(key, Collections.emptySet()));
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (String edgeKey : edgeKeys) {
            GraphEdge edge = scope.edges.get(edgeKey);
            if (edge == null || !visited.contains(edge.getFromKey()) || !visited.contains(edge.getToKey())) {
                continue;
            }
            edges.add(copy(edge));
        }
        edges.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return new Subgraph(nodes, edges);
    }

    @Override
    public long nodeCount(String ontologyId, String workspace) {
        Scope scope = scopes.get(scopeKey(ontologyId, workspace));
        return scope == null ? 0 : scope.nodes.size();
    }

    @Override
    public long edgeCount(String ontologyId, String workspace) {
        Scope scope = scopes.get(scopeKey(ontologyId, workspace));
        return scope == null ? 0 : scope.edges.size();
    }

    private static String scopeKey(String ontologyId, String workspace) {
        return ontologyId + "\u0000" + workspace;
    }

    private Scope scopeOf(String ontologyId, String workspace) {
        return scopes.computeIfAbsent(scopeKey(ontologyId, workspace), k -> new Scope());
    }

    private static GraphNode copy(GraphNode node) {
        return node == null ? null : new GraphNode(node.getKey(), node.getLabels(),
                new LinkedHashMap<>(node.getProperties()));
    }

    private static GraphEdge copy(GraphEdge edge) {
        return edge == null ? null : new GraphEdge(edge.getKey(), edge.getType(), edge.getFromKey(),
                edge.getToKey(), new LinkedHashMap<>(edge.getProperties()));
    }

    private static final class Scope {
        private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
        private final Map<String, GraphEdge> edges = new LinkedHashMap<>();
        private final Map<String, Set<String>> outEdges = new LinkedHashMap<>();
        private final Map<String, Set<String>> inEdges = new LinkedHashMap<>();
    }

    private static final class PendingOp {
        static final int RESET = 0;
        static final int NODE = 1;
        static final int EDGE = 2;

        final int kind;
        final String ontologyId;
        final String workspace;
        final String key;
        final String type;
        final String fromKey;
        final String toKey;
        final Collection<String> labels;
        final Map<String, Object> properties;

        private PendingOp(int kind, String ontologyId, String workspace, String key, String type,
                          String fromKey, String toKey, Collection<String> labels, Map<String, Object> properties) {
            this.kind = kind;
            this.ontologyId = ontologyId;
            this.workspace = workspace;
            this.key = key;
            this.type = type;
            this.fromKey = fromKey;
            this.toKey = toKey;
            this.labels = labels;
            this.properties = properties;
        }

        static PendingOp reset(String ontologyId, String workspace) {
            return new PendingOp(RESET, ontologyId, workspace, null, null, null, null, null, null);
        }

        static PendingOp node(String ontologyId, String workspace, String key,
                              Collection<String> labels, Map<String, Object> properties) {
            return new PendingOp(NODE, ontologyId, workspace, key, null, null, null, labels, properties);
        }

        static PendingOp edge(String ontologyId, String workspace, String key, String type,
                              String fromKey, String toKey, Map<String, Object> properties) {
            return new PendingOp(EDGE, ontologyId, workspace, key, type, fromKey, toKey, null, properties);
        }
    }
}
