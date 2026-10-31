package com.zifang.z.ontology.graph.store;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.Node;
import com.zifang.z.graph.core.InMemoryGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件持久图存储：内存引擎 + JSONL 追加日志。
 *
 * <p>每次变更追加一行（节点/边的增删改），启动时重放日志并压实成快照；
 * 运行中日志行数超过 max(4096, 2×活跃元素数) 时自动压实。崩溃最多丢最后未刷盘的一行。</p>
 *
 * <p>快照只记节点的主标签（z-ontology 物化路径本来就是单标签 + labels 属性）；
 * Tag/属性索引等控制面操作不进日志，重启后如需可再声明。</p>
 */
public class JsonlGraphStore extends InMemoryGraphStore {

    private static final Logger log = LoggerFactory.getLogger(JsonlGraphStore.class);

    private final Path file;
    private BufferedWriter writer;
    private long opsSinceCompact;

    public JsonlGraphStore(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.exists(file)) {
                replay(file);
                log.info("已重放图日志 {}：{} 节点 / {} 边，并压实为快照", file, getNodeCount(), getEdgeCount());
                compact();
            }
            writer = newWriter();
        } catch (IOException e) {
            throw new IllegalStateException("cannot initialize graph journal " + file, e);
        }
    }

    @Override
    public synchronized Node addNode(String label, Map<String, Object> properties) {
        Node node = super.addNode(label, properties);
        append(nodeLine(node.getId(), label, properties));
        return node;
    }

    @Override
    public synchronized Node addNode(long id, String label, Map<String, Object> properties) {
        Node node = super.addNode(id, label, properties);
        append(nodeLine(id, label, properties));
        return node;
    }

    @Override
    public synchronized void updateNode(long id, Map<String, Object> properties) {
        super.updateNode(id, properties);
        append("{\"op\":\"UN\",\"id\":" + id + ",\"props\":" + jsonProps(properties) + "}");
    }

    @Override
    public synchronized boolean removeNode(long id) {
        boolean removed = super.removeNode(id);
        if (removed) append("{\"op\":\"RN\",\"id\":" + id + "}");
        return removed;
    }

    @Override
    public synchronized Edge addEdge(String type, long startNodeId, long endNodeId, Map<String, Object> properties) {
        Edge edge = super.addEdge(type, startNodeId, endNodeId, properties);
        append(edgeLine(edge));
        return edge;
    }

    @Override
    public synchronized Edge addEdge(long id, String type, long startNodeId, long endNodeId,
                                     Map<String, Object> properties) {
        Edge edge = super.addEdge(id, type, startNodeId, endNodeId, properties);
        append(edgeLine(edge));
        return edge;
    }

    @Override
    public synchronized void updateEdge(long id, Map<String, Object> properties) {
        super.updateEdge(id, properties);
        append("{\"op\":\"UE\",\"id\":" + id + ",\"props\":" + jsonProps(properties) + "}");
    }

    @Override
    public synchronized boolean removeEdge(long id) {
        boolean removed = super.removeEdge(id);
        if (removed) append("{\"op\":\"RE\",\"id\":" + id + "}");
        return removed;
    }

    @Override
    public synchronized void commit() {
        flush();
    }

    @Override
    public synchronized void rollback() {
        flush();
    }

    /** 关闭日志句柄（压实后重开）。 */
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private BufferedWriter newWriter() throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void replay(Path file) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                apply(JsonUtil.parseToMap(line));
            } catch (RuntimeException e) {
                log.warn("跳过无法解析的图日志行: {}", e.toString());
            }
        }
    }

    private void apply(Map<String, Object> op) {
        String kind = String.valueOf(op.get("op"));
        long id = ((Number) op.get("id")).longValue();
        switch (kind) {
            case "AN" -> super.addNode(id, String.valueOf(op.get("label")), readProps(op.get("props")));
            case "AE" -> super.addEdge(id, String.valueOf(op.get("type")),
                    ((Number) op.get("from")).longValue(), ((Number) op.get("to")).longValue(),
                    readProps(op.get("props")));
            case "UN" -> super.updateNode(id, readProps(op.get("props")));
            case "UE" -> super.updateEdge(id, readProps(op.get("props")));
            case "RN" -> super.removeNode(id);
            case "RE" -> super.removeEdge(id);
            default -> log.warn("未知图日志操作 {}，跳过", kind);
        }
    }

    private static Map<String, Object> readProps(Object raw) {
        // parseToMap 的嵌套值是 JsonObject/JsonArray，必须先摊平成原生 Map/List
        Object plain = JsonUtil.toPlainValue(raw);
        if (plain instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) plain;
            return new LinkedHashMap<>(props);
        }
        return new LinkedHashMap<>();
    }

    private void append(String line) {
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("cannot append graph journal " + file, e);
        }
        opsSinceCompact++;
        long live = getNodeCount() + getEdgeCount();
        if (opsSinceCompact >= Math.max(4096, 2 * live)) {
            try {
                compact();
            } catch (IOException e) {
                throw new IllegalStateException("cannot compact graph journal " + file, e);
            }
        }
    }

    private void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("cannot flush graph journal " + file, e);
        }
    }

    private void compact() throws IOException {
        if (writer != null) writer.close();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Node node : getAllNodes()) {
                w.write(nodeLine(node.getId(), node.getLabels().isEmpty() ? "" : node.getLabels().iterator().next(),
                        node.getProperties()));
                w.write('\n');
            }
            for (Edge edge : getAllEdges()) {
                w.write(edgeLine(edge));
                w.write('\n');
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        writer = newWriter();
        opsSinceCompact = 0;
    }

    private static String nodeLine(long id, String label, Map<String, Object> properties) {
        return "{\"op\":\"AN\",\"id\":" + id + ",\"label\":" + jsonString(label)
                + ",\"props\":" + jsonProps(properties) + "}";
    }

    private static String edgeLine(Edge edge) {
        return "{\"op\":\"AE\",\"id\":" + edge.getId() + ",\"type\":" + jsonString(edge.getType())
                + ",\"from\":" + edge.getStartNodeId() + ",\"to\":" + edge.getEndNodeId()
                + ",\"props\":" + jsonProps(edge.getProperties()) + "}";
    }

    /** 只保留 JSON 可表达的数据类型（Bolt 子集），其余值降级为字符串。 */
    private static String jsonProps(Map<String, Object> properties) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            safe.put(entry.getKey(), toSerializable(entry.getValue()));
        }
        return JsonUtil.toJson(safe);
    }

    private static Object toSerializable(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object item : (List<?>) value) list.add(toSerializable(item));
            return list;
        }
        return String.valueOf(value);
    }

    private static String jsonString(String value) {
        return JsonUtil.toJson(value);
    }
}
