package com.zifang.z.ontology.graph.adapter;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.api.Node;
import com.zifang.z.ontology.api.exception.OntologyException;
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
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 用 z-graph 的 {@link GraphStore} 承载本体物化图。
 *
 * <p>本体层的 key 是字符串（class:Foo / individual:bar），z-graph 用 long id，
 * 因此这里维护 scope(ontologyId + workspace) 内的双向映射。每个 scope 的节点和边
 * 都由本适配器创建并在 reset 时整批清除，不会误删宿主图里其他内容。</p>
 */
public class ZGraphOntologyPort implements OntologyGraphPort {

    private static final String DEFAULT_LABEL = "OntologyNode";
    private static final String LABELS_PROPERTY = "labels";
    private static final String KEY_PROPERTY = "key";
    private static final char SCOPE_SEPARATOR = '\u0000';
    private static final char KEY_SEPARATOR = '\u0001';

    private final GraphStore store;
    private final Map<String, ScopeIndex> scopes = new ConcurrentHashMap<>();

    public ZGraphOntologyPort(GraphStore store) {
        if (store == null) {
            throw new IllegalArgumentException("GraphStore is required");
        }
        this.store = store;
    }

    @Override
    public synchronized void reset(String ontologyId, String workspace) {
        ScopeIndex index = scopes.remove(scopeOf(ontologyId, workspace));
        if (index == null) {
            return;
        }
        for (Long edgeId : index.edgeIds()) {
            store.removeEdge(edgeId);
        }
        for (Long nodeId : index.nodeIds()) {
            store.removeNode(nodeId);
        }
    }

    @Override
    public synchronized void upsertNode(String ontologyId, String workspace, String key,
                                        Collection<String> labels, Map<String, Object> properties) {
        if (key == null || key.isEmpty()) {
            throw new OntologyException("node key is required");
        }
        String scope = scopeOf(ontologyId, workspace);
        ScopeIndex index = indexOf(scope);
        String scopedKey = scopedKey(scope, key);
        List<String> safeLabels = labels == null ? new ArrayList<>() : new ArrayList<>(labels);

        Map<String, Object> props = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        props.put(LABELS_PROPERTY, new ArrayList<>(safeLabels));

        Long existingId = index.nodeIdByKey.get(scopedKey);
        if (existingId != null && store.getNode(existingId) != null) {
            store.updateNode(existingId, props);
            return;
        }
        String primaryLabel = safeLabels.isEmpty() ? DEFAULT_LABEL : safeLabels.get(0);
        Node node = store.addNode(primaryLabel, props);
        index.nodeIdByKey.put(scopedKey, node.getId());
        index.nodeKeyById.put(node.getId(), scopedKey);
        index.nodeKeys.add(scopedKey);
    }

    @Override
    public synchronized void upsertEdge(String ontologyId, String workspace, String key, String type,
                                        String fromKey, String toKey, Map<String, Object> properties) {
        if (key == null || key.isEmpty()) {
            throw new OntologyException("edge key is required");
        }
        if (type == null || type.isEmpty()) {
            throw new OntologyException("edge type is required for " + key);
        }
        String scope = scopeOf(ontologyId, workspace);
        ScopeIndex index = indexOf(scope);
        String scopedKey = scopedKey(scope, key);
        String scopedFrom = scopedKey(scope, fromKey);
        String scopedTo = scopedKey(scope, toKey);

        Map<String, Object> props = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);

        Long existingId = index.edgeIdByKey.get(scopedKey);
        if (existingId != null && store.getEdge(existingId) != null) {
            store.updateEdge(existingId, props);
            return;
        }
        Long fromId = index.nodeIdByKey.get(scopedFrom);
        Long toId = index.nodeIdByKey.get(scopedTo);
        if (fromId == null || toId == null) {
            throw new OntologyException("cannot create edge " + key + ": endpoint node missing (from="
                    + fromKey + ", to=" + toKey + ")");
        }
        Edge edge = store.addEdge(type, fromId, toId, props);
        index.edgeIdByKey.put(scopedKey, edge.getId());
        index.edgeKeyById.put(edge.getId(), scopedKey);
        index.edgeKeys.add(scopedKey);
    }

    @Override
    public GraphNode findNode(String ontologyId, String workspace, String key) {
        ScopeIndex index = scopes.get(scopeOf(ontologyId, workspace));
        if (index == null) {
            return null;
        }
        Long nodeId = index.nodeIdByKey.get(scopedKey(scopeOf(ontologyId, workspace), key));
        if (nodeId == null) {
            return null;
        }
        Node node = store.getNode(nodeId);
        return node == null ? null : toGraphNode(index, node);
    }

    @Override
    public List<GraphNode> listNodes(String ontologyId, String workspace) {
        ScopeIndex index = scopes.get(scopeOf(ontologyId, workspace));
        if (index == null) {
            return Collections.emptyList();
        }
        List<GraphNode> nodes = new ArrayList<>();
        for (String scopedKey : index.nodeKeys) {
            Long nodeId = index.nodeIdByKey.get(scopedKey);
            if (nodeId == null) {
                continue;
            }
            Node node = store.getNode(nodeId);
            if (node != null) {
                nodes.add(toGraphNode(index, node));
            }
        }
        nodes.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return nodes;
    }

    @Override
    public List<Neighbor> neighbors(String ontologyId, String workspace, String key, int depth, String edgeType) {
        ScopeIndex index = scopes.get(scopeOf(ontologyId, workspace));
        if (index == null || depth <= 0) {
            return Collections.emptyList();
        }
        Long startId = index.nodeIdByKey.get(scopedKey(scopeOf(ontologyId, workspace), key));
        if (startId == null) {
            return Collections.emptyList();
        }
        return breadthFirst(index, startId, depth, edgeType, true);
    }

    @Override
    public Subgraph subgraph(String ontologyId, String workspace, Collection<String> keys, int depth) {
        ScopeIndex index = scopes.get(scopeOf(ontologyId, workspace));
        if (index == null || keys == null || keys.isEmpty()) {
            return new Subgraph();
        }
        String scope = scopeOf(ontologyId, workspace);
        Set<Long> visited = new LinkedHashSet<>();
        Deque<long[]> queue = new ArrayDeque<>();
        for (String key : keys) {
            Long nodeId = index.nodeIdByKey.get(scopedKey(scope, key));
            if (nodeId != null && visited.add(nodeId)) {
                queue.add(new long[]{nodeId, 0});
            }
        }
        int safeDepth = Math.max(0, depth);
        while (!queue.isEmpty()) {
            long[] current = queue.poll();
            if (current[1] >= safeDepth) {
                continue;
            }
            for (Edge edge : store.getEdges(current[0])) {
                if (index.edgeKeyById.get(edge.getId()) == null) {
                    continue;
                }
                long otherId = edge.getStartNodeId() == current[0] ? edge.getEndNodeId() : edge.getStartNodeId();
                if (visited.add(otherId)) {
                    queue.add(new long[]{otherId, current[1] + 1});
                }
            }
        }

        List<GraphNode> nodes = new ArrayList<>();
        for (Long nodeId : visited) {
            Node node = store.getNode(nodeId);
            if (node != null) {
                nodes.add(toGraphNode(index, node));
            }
        }
        nodes.sort((a, b) -> a.getKey().compareTo(b.getKey()));

        Set<String> edgeKeys = new LinkedHashSet<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (Long nodeId : visited) {
            for (Edge edge : store.getEdges(nodeId)) {
                String edgeKey = index.edgeKeyById.get(edge.getId());
                if (edgeKey == null || !edgeKeys.add(edgeKey)) {
                    continue;
                }
                if (!visited.contains(edge.getStartNodeId()) || !visited.contains(edge.getEndNodeId())) {
                    continue;
                }
                edges.add(toGraphEdge(index, edge));
            }
        }
        edges.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return new Subgraph(nodes, edges);
    }

    @Override
    public long nodeCount(String ontologyId, String workspace) {
        ScopeIndex index = scopes.get(scopeOf(ontologyId, workspace));
        return index == null ? 0 : index.nodeKeys.size();
    }

    @Override
    public long edgeCount(String ontologyId, String workspace) {
        ScopeIndex index = scopes.get(scopeOf(ontologyId, workspace));
        return index == null ? 0 : index.edgeKeys.size();
    }

    private List<Neighbor> breadthFirst(ScopeIndex index, long startId, int depth, String edgeType,
                                        boolean bothDirections) {
        List<Neighbor> result = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(startId);

        Deque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{startId, 0});
        while (!queue.isEmpty()) {
            long[] current = queue.poll();
            int currentDepth = (int) current[1];
            if (currentDepth >= depth) {
                continue;
            }
            List<Edge> outgoing = store.getOutEdges(current[0]);
            for (Edge edge : outgoing) {
                if (edgeType != null && !edgeType.equals(edge.getType())) {
                    continue;
                }
                addNeighbor(index, edge, edge.getEndNodeId(), "OUT", currentDepth + 1, visited, queue, result);
            }
            if (!bothDirections) {
                continue;
            }
            List<Edge> incoming = store.getInEdges(current[0]);
            for (Edge edge : incoming) {
                if (edgeType != null && !edgeType.equals(edge.getType())) {
                    continue;
                }
                addNeighbor(index, edge, edge.getStartNodeId(), "IN", currentDepth + 1, visited, queue, result);
            }
        }
        return result;
    }

    private void addNeighbor(ScopeIndex index, Edge edge, long neighborId, String direction, int depth,
                             Set<Long> visited, Deque<long[]> queue, List<Neighbor> result) {
        if (!visited.add(neighborId)) {
            return;
        }
        Node neighborNode = store.getNode(neighborId);
        if (neighborNode == null) {
            return;
        }
        result.add(new Neighbor(toGraphNode(index, neighborNode), toGraphEdge(index, edge), direction, depth));
        queue.add(new long[]{neighborId, depth});
    }

    private GraphNode toGraphNode(ScopeIndex index, Node node) {
        String scopedKey = index.nodeKeyById.get(node.getId());
        String key = scopedKey == null ? String.valueOf(node.getId()) : localKey(scopedKey);
        List<String> labels = new ArrayList<>();
        Object stored = node.get(LABELS_PROPERTY);
        if (stored instanceof Collection) {
            for (Object label : (Collection<?>) stored) {
                if (label != null) {
                    labels.add(String.valueOf(label));
                }
            }
        }
        if (labels.isEmpty()) {
            labels.addAll(node.getLabels());
        }
        Map<String, Object> props = new LinkedHashMap<>(node.getProperties());
        props.remove(LABELS_PROPERTY);
        return new GraphNode(key, labels, props);
    }

    private GraphEdge toGraphEdge(ScopeIndex index, Edge edge) {
        String scopedKey = index.edgeKeyById.get(edge.getId());
        String key = scopedKey == null ? String.valueOf(edge.getId()) : localKey(scopedKey);
        String fromScoped = index.nodeKeyById.get(edge.getStartNodeId());
        String toScoped = index.nodeKeyById.get(edge.getEndNodeId());
        String fromKey = fromScoped == null ? String.valueOf(edge.getStartNodeId()) : localKey(fromScoped);
        String toKey = toScoped == null ? String.valueOf(edge.getEndNodeId()) : localKey(toScoped);
        return new GraphEdge(key, edge.getType(), fromKey, toKey, new LinkedHashMap<>(edge.getProperties()));
    }

    private ScopeIndex indexOf(String scope) {
        return scopes.computeIfAbsent(scope, s -> new ScopeIndex());
    }

    private static String scopeOf(String ontologyId, String workspace) {
        return (ontologyId == null ? "" : ontologyId) + SCOPE_SEPARATOR + (workspace == null ? "" : workspace);
    }

    private static String scopedKey(String scope, String key) {
        return scope + KEY_SEPARATOR + key;
    }

    private static String localKey(String scopedKey) {
        int index = scopedKey.indexOf(KEY_SEPARATOR);
        return index < 0 ? scopedKey : scopedKey.substring(index + 1);
    }

    private static final class ScopeIndex {
        private final Map<String, Long> nodeIdByKey = new ConcurrentHashMap<>();
        private final Map<Long, String> nodeKeyById = new ConcurrentHashMap<>();
        private final Map<String, Long> edgeIdByKey = new ConcurrentHashMap<>();
        private final Map<Long, String> edgeKeyById = new ConcurrentHashMap<>();
        private final Set<String> nodeKeys = new CopyOnWriteArraySet<>();
        private final Set<String> edgeKeys = new CopyOnWriteArraySet<>();

        private Set<Long> nodeIds() {
            return nodeKeyById.keySet();
        }

        private Set<Long> edgeIds() {
            return edgeKeyById.keySet();
        }
    }
}
