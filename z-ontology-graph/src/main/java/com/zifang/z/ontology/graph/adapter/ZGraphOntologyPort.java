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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 用 z-graph 的 {@link GraphStore} 承载本体物化图。
 *
 * <p>本体层的 key 是字符串（class:Foo / individual:bar），z-graph 用 long id，
 * 因此这里维护 scope(ontologyId + workspace) 内的双向映射。每个 scope 的节点和边
 * 都由本适配器创建并在 reset 时整批清除，不会误删宿主图里其他内容。</p>
 *
 * <p>scope 索引支持从 store 懒重建：写入时把 scoped key 存进节点/边的
 * {@code __ontKey} 属性，进程重启后第一次访问该 scope 时扫描 store 重建索引
 * （对旧版本写入的、没有 {@code __ontKey} 的数据，按物化器的属性约定回推 key）。
 * 这消除了「重启即失忆」的问题。</p>
 *
 * <p>支持批量原子写（{@link #supportsAtomicBatch()}）：begin 后的 reset/upsert
 * 先进暂存区并做参数与端点校验，commit 一次性按序落盘，rollback 整批丢弃；
 * 物化中途失败因此不留半成品。</p>
 */
public class ZGraphOntologyPort implements OntologyGraphPort {

    private static final String DEFAULT_LABEL = "OntologyNode";
    private static final String LABELS_PROPERTY = "labels";
    private static final String ONT_KEY_PROPERTY = "__ontKey";
    private static final char SCOPE_SEPARATOR = '\u0000';
    private static final char KEY_SEPARATOR = '\u0001';

    private static final int OP_RESET = 0;
    private static final int OP_NODE = 1;
    private static final int OP_EDGE = 2;

    private final GraphStore store;
    private final Map<String, ScopeIndex> scopes = new ConcurrentHashMap<>();
    private List<PendingOp> batch;
    private Map<String, Set<String>> stagedNodeKeys;
    private Set<String> stagedResets;

    public ZGraphOntologyPort(GraphStore store) {
        if (store == null) {
            throw new IllegalArgumentException("GraphStore is required");
        }
        this.store = store;
    }

    @Override
    public boolean supportsAtomicBatch() {
        return true;
    }

    @Override
    public synchronized void beginAtomicBatch() {
        if (batch != null) {
            throw new OntologyException("atomic batch already active");
        }
        batch = new ArrayList<>();
        stagedNodeKeys = new HashMap<>();
        stagedResets = new LinkedHashSet<>();
    }

    @Override
    public synchronized void commitAtomicBatch() {
        List<PendingOp> ops = batch;
        batch = null;
        stagedNodeKeys = null;
        stagedResets = null;
        if (ops == null) {
            return;
        }
        for (PendingOp op : ops) {
            switch (op.kind) {
                case OP_RESET:
                    doReset(op.ontologyId, op.workspace);
                    break;
                case OP_NODE:
                    doUpsertNode(op.ontologyId, op.workspace, op.key, op.labels, op.properties);
                    break;
                case OP_EDGE:
                    doUpsertEdge(op.ontologyId, op.workspace, op.key, op.type,
                            op.fromKey, op.toKey, op.properties);
                    break;
                default:
                    throw new OntologyException("unknown pending op: " + op.kind);
            }
        }
    }

    @Override
    public synchronized void rollbackAtomicBatch() {
        batch = null;
        stagedNodeKeys = null;
        stagedResets = null;
    }

    @Override
    public synchronized void reset(String ontologyId, String workspace) {
        if (staging()) {
            stage(PendingOp.reset(ontologyId, workspace));
            return;
        }
        doReset(ontologyId, workspace);
    }

    @Override
    public synchronized void upsertNode(String ontologyId, String workspace, String key,
                                        Collection<String> labels, Map<String, Object> properties) {
        if (staging()) {
            if (key == null || key.isEmpty()) {
                throw new OntologyException("node key is required");
            }
            stage(PendingOp.node(ontologyId, workspace, key, labels, properties));
            stagedNodeKeys.computeIfAbsent(scopeOf(ontologyId, workspace), s -> new LinkedHashSet<>()).add(key);
            return;
        }
        doUpsertNode(ontologyId, workspace, key, labels, properties);
    }

    @Override
    public synchronized void upsertEdge(String ontologyId, String workspace, String key, String type,
                                        String fromKey, String toKey, Map<String, Object> properties) {
        if (staging()) {
            if (key == null || key.isEmpty()) {
                throw new OntologyException("edge key is required");
            }
            if (type == null || type.isEmpty()) {
                throw new OntologyException("edge type is required for " + key);
            }
            String scope = scopeOf(ontologyId, workspace);
            if (!endpointAvailable(scope, fromKey) || !endpointAvailable(scope, toKey)) {
                throw new OntologyException("cannot create edge " + key + ": endpoint node missing (from="
                        + fromKey + ", to=" + toKey + ")");
            }
            stage(PendingOp.edge(ontologyId, workspace, key, type, fromKey, toKey, properties));
            return;
        }
        doUpsertEdge(ontologyId, workspace, key, type, fromKey, toKey, properties);
    }

    private boolean staging() {
        return batch != null;
    }

    private void stage(PendingOp op) {
        if (op.kind == OP_RESET) {
            String scope = scopeOf(op.ontologyId, op.workspace);
            stagedResets.add(scope);
            stagedNodeKeys.remove(scope);
        }
        batch.add(op);
    }

    /** 暂存期端点校验：批内已 staged 的节点，或未 staged reset 且 live 索引里存在的节点。 */
    private boolean endpointAvailable(String scope, String key) {
        Set<String> staged = stagedNodeKeys.get(scope);
        if (staged != null && staged.contains(key)) {
            return true;
        }
        if (stagedResets.contains(scope)) {
            return false;
        }
        ScopeIndex index = scopes.get(scope);
        return index != null && index.nodeIdByKey.containsKey(scopedKey(scope, key));
    }

    private void doReset(String ontologyId, String workspace) {
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

    private void doUpsertNode(String ontologyId, String workspace, String key,
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
        props.put(ONT_KEY_PROPERTY, scopedKey);

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

    private void doUpsertEdge(String ontologyId, String workspace, String key, String type,
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
        props.put(ONT_KEY_PROPERTY, scopedKey);

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
        String scope = scopeOf(ontologyId, workspace);
        ScopeIndex index = indexOf(scope);
        Long nodeId = index.nodeIdByKey.get(scopedKey(scope, key));
        if (nodeId == null) {
            return null;
        }
        Node node = store.getNode(nodeId);
        return node == null ? null : toGraphNode(index, node);
    }

    @Override
    public List<GraphNode> listNodes(String ontologyId, String workspace) {
        ScopeIndex index = indexOf(scopeOf(ontologyId, workspace));
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
        String scope = scopeOf(ontologyId, workspace);
        ScopeIndex index = indexOf(scope);
        if (depth <= 0) {
            return Collections.emptyList();
        }
        Long startId = index.nodeIdByKey.get(scopedKey(scope, key));
        if (startId == null) {
            return Collections.emptyList();
        }
        return breadthFirst(index, startId, depth, edgeType);
    }

    @Override
    public Subgraph subgraph(String ontologyId, String workspace, Collection<String> keys, int depth) {
        String scope = scopeOf(ontologyId, workspace);
        ScopeIndex index = indexOf(scope);
        if (keys == null || keys.isEmpty()) {
            return new Subgraph();
        }
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
        return indexOf(scopeOf(ontologyId, workspace)).nodeKeys.size();
    }

    @Override
    public long edgeCount(String ontologyId, String workspace) {
        return indexOf(scopeOf(ontologyId, workspace)).edgeKeys.size();
    }

    /**
     * BFS 邻居：节点只入队一次，但每条符合 edgeType 的边恰好报告一次
     * （平行边全部返回，方向按发现它的那一侧计）。
     */
    private List<Neighbor> breadthFirst(ScopeIndex index, long startId, int depth, String edgeType) {
        List<Neighbor> result = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        Set<Long> seenEdges = new LinkedHashSet<>();
        visited.add(startId);

        Deque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{startId, 0});
        while (!queue.isEmpty()) {
            long[] current = queue.poll();
            int currentDepth = (int) current[1];
            if (currentDepth >= depth) {
                continue;
            }
            for (Edge edge : store.getOutEdges(current[0])) {
                if (edgeType != null && !edgeType.equals(edge.getType())) {
                    continue;
                }
                reportEdge(index, current[0], edge, edge.getEndNodeId(), "OUT", currentDepth + 1,
                        visited, seenEdges, queue, result);
            }
            for (Edge edge : store.getInEdges(current[0])) {
                if (edgeType != null && !edgeType.equals(edge.getType())) {
                    continue;
                }
                reportEdge(index, current[0], edge, edge.getStartNodeId(), "IN", currentDepth + 1,
                        visited, seenEdges, queue, result);
            }
        }
        return result;
    }

    /** 每条符合 edgeType 的边恰好报告一次（平行边全部返回）；节点只入队一次；自环边跳过。 */
    private void reportEdge(ScopeIndex index, long currentId, Edge edge, long neighborId, String direction,
                            int depth, Set<Long> visited, Set<Long> seenEdges,
                            Deque<long[]> queue, List<Neighbor> result) {
        if (currentId == neighborId || !seenEdges.add(edge.getId())) {
            return;
        }
        Node neighborNode = store.getNode(neighborId);
        if (neighborNode == null) {
            return;
        }
        result.add(new Neighbor(toGraphNode(index, neighborNode), toGraphEdge(index, edge), direction, depth));
        if (visited.add(neighborId)) {
            queue.add(new long[]{neighborId, depth});
        }
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
        props.remove(ONT_KEY_PROPERTY);
        return new GraphNode(key, labels, props);
    }

    private GraphEdge toGraphEdge(ScopeIndex index, Edge edge) {
        String scopedKey = index.edgeKeyById.get(edge.getId());
        String key = scopedKey == null ? String.valueOf(edge.getId()) : localKey(scopedKey);
        String fromScoped = index.nodeKeyById.get(edge.getStartNodeId());
        String toScoped = index.nodeKeyById.get(edge.getEndNodeId());
        String fromKey = fromScoped == null ? String.valueOf(edge.getStartNodeId()) : localKey(fromScoped);
        String toKey = toScoped == null ? String.valueOf(edge.getEndNodeId()) : localKey(toScoped);
        Map<String, Object> props = new LinkedHashMap<>(edge.getProperties());
        props.remove(ONT_KEY_PROPERTY);
        return new GraphEdge(key, edge.getType(), fromKey, toKey, props);
    }

    private ScopeIndex indexOf(String scope) {
        ScopeIndex index = scopes.get(scope);
        if (index != null) {
            return index;
        }
        return scopes.computeIfAbsent(scope, this::rebuildIndex);
    }

    /**
     * 从 store 扫描重建 scope 索引：新数据带 {@code __ontKey}；旧数据按物化器
     * 的属性约定（ontologyId/workspace + name/key）回推。回推出的 key 无法唯一
     * 确定时跳过该实体，等下一次物化 reset+重写自愈。
     */
    private ScopeIndex rebuildIndex(String scope) {
        ScopeIndex index = new ScopeIndex();
        String prefix = scope + KEY_SEPARATOR;
        for (Node node : store.getAllNodes()) {
            String scopedKey = storedKey(node.get(ONT_KEY_PROPERTY), prefix);
            if (scopedKey == null) {
                scopedKey = legacyNodeKey(node, scope);
            }
            if (scopedKey == null || !scopedKey.startsWith(prefix)) {
                continue;
            }
            index.nodeIdByKey.put(scopedKey, node.getId());
            index.nodeKeyById.put(node.getId(), scopedKey);
            index.nodeKeys.add(scopedKey);
        }
        for (Edge edge : store.getAllEdges()) {
            String scopedKey = storedKey(edge.get(ONT_KEY_PROPERTY), prefix);
            if (scopedKey == null) {
                scopedKey = legacyEdgeKey(index, edge, scope);
            }
            if (scopedKey == null || !scopedKey.startsWith(prefix)) {
                continue;
            }
            if (!index.nodeKeyById.containsKey(edge.getStartNodeId())
                    || !index.nodeKeyById.containsKey(edge.getEndNodeId())) {
                continue;
            }
            if (index.edgeIdByKey.containsKey(scopedKey)) {
                continue;
            }
            index.edgeIdByKey.put(scopedKey, edge.getId());
            index.edgeKeyById.put(edge.getId(), scopedKey);
            index.edgeKeys.add(scopedKey);
        }
        return index;
    }

    private static String storedKey(Object stored, String prefix) {
        return stored instanceof String && ((String) stored).startsWith(prefix) ? (String) stored : null;
    }

    /** 旧版本数据没有 __ontKey：按物化器写入的属性回推 key。 */
    private static String legacyNodeKey(Node node, String scope) {
        int split = scope.indexOf(SCOPE_SEPARATOR);
        String ontologyId = scope.substring(0, split);
        String workspace = scope.substring(split + 1);
        Map<String, Object> props = node.getProperties();
        if (!Objects.equals(props.get("ontologyId"), ontologyId)
                || !Objects.equals(props.get("workspace"), workspace)) {
            return null;
        }
        if (node.getLabels() != null && node.getLabels().contains("OntologyClass") && props.get("name") != null) {
            return legacyScoped(scope, "class:" + props.get("name"));
        }
        if (props.get("key") != null) {
            return legacyScoped(scope, "individual:" + props.get("key"));
        }
        return null;
    }

    /** 旧版本边没有 __ontKey：按物化器的边 key 约定（subclass:/instance:/stmt:）回推。 */
    private static String legacyEdgeKey(ScopeIndex index, Edge edge, String scope) {
        String fromScoped = index.nodeKeyById.get(edge.getStartNodeId());
        String toScoped = index.nodeKeyById.get(edge.getEndNodeId());
        if (fromScoped == null || toScoped == null) {
            return null;
        }
        String from = localKey(fromScoped);
        String to = localKey(toScoped);
        String derived;
        switch (edge.getType()) {
            case "SUB_CLASS_OF":
                derived = "subclass:" + from + ":" + to;
                break;
            case "INSTANCE_OF":
                derived = "instance:" + stripIndividual(from) + ":" + to;
                break;
            default:
                derived = "stmt:" + stripIndividual(from) + ":" + edge.getType() + ":" + stripIndividual(to);
                break;
        }
        return legacyScoped(scope, derived);
    }

    private static String stripIndividual(String key) {
        return key.startsWith("individual:") ? key.substring("individual:".length()) : key;
    }

    private static String legacyScoped(String scope, String localKey) {
        return scope + KEY_SEPARATOR + localKey;
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

    private static final class PendingOp {
        private static final int RESET = OP_RESET;
        private static final int NODE = OP_NODE;
        private static final int EDGE = OP_EDGE;

        private final int kind;
        private final String ontologyId;
        private final String workspace;
        private final String key;
        private final String type;
        private final String fromKey;
        private final String toKey;
        private final Collection<String> labels;
        private final Map<String, Object> properties;

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

        private static PendingOp reset(String ontologyId, String workspace) {
            return new PendingOp(RESET, ontologyId, workspace, null, null, null, null, null, null);
        }

        private static PendingOp node(String ontologyId, String workspace, String key,
                                      Collection<String> labels, Map<String, Object> properties) {
            return new PendingOp(NODE, ontologyId, workspace, key, null, null, null, labels, properties);
        }

        private static PendingOp edge(String ontologyId, String workspace, String key, String type,
                                      String fromKey, String toKey, Map<String, Object> properties) {
            return new PendingOp(EDGE, ontologyId, workspace, key, type, fromKey, toKey, null, properties);
        }
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
