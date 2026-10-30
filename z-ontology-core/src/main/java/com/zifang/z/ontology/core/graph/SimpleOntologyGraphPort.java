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

    @Override
    public synchronized void reset(String ontologyId, String workspace) {
        scopes.remove(scopeKey(ontologyId, workspace));
    }

    @Override
    public synchronized void upsertNode(String ontologyId, String workspace, String key,
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
                if (visited.add(edge.getToKey())) {
                    result.add(new Neighbor(copy(scope.nodes.get(edge.getToKey())), copy(edge), "OUT", currentDepth + 1));
                    queue.add(new String[]{edge.getToKey(), String.valueOf(currentDepth + 1)});
                }
            }
            for (String edgeKey : scope.inEdges.getOrDefault(current[0], Collections.emptySet())) {
                GraphEdge edge = scope.edges.get(edgeKey);
                if (edge == null || (edgeType != null && !edgeType.equals(edge.getType()))) {
                    continue;
                }
                if (visited.add(edge.getFromKey())) {
                    result.add(new Neighbor(copy(scope.nodes.get(edge.getFromKey())), copy(edge), "IN", currentDepth + 1));
                    queue.add(new String[]{edge.getFromKey(), String.valueOf(currentDepth + 1)});
                }
            }
        }
        return result;
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
}
