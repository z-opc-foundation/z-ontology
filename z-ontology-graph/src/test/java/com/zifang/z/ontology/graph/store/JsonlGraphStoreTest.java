package com.zifang.z.ontology.graph.store;

import com.zifang.z.graph.api.Edge;
import com.zifang.z.graph.api.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlGraphStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("graph.jsonl");
    }

    @Test
    void survivesReopen() {
        JsonlGraphStore first = new JsonlGraphStore(file());
        Node alice = first.addNode("Person", Map.of("key", "alice", "note", "引号\" 换行\n emoji 😀"));
        Node acme = first.addNode("Org", Map.of("key", "acme", "tags", List.of("a", "b"),
                "weight", 2, "active", true));
        first.addEdge("WORKS_FOR", alice.getId(), acme.getId(), Map.of("since", 2020));
        first.addNode("Ghost", Map.of());
        first.removeNode(first.getNodeIdsByLabel("Ghost").get(0));

        JsonlGraphStore second = new JsonlGraphStore(file());
        assertEquals(2, second.getNodeCount());
        assertEquals(1, second.getEdgeCount());

        Node reloaded = second.getNode(alice.getId());
        assertEquals("Person", reloaded.getLabels().iterator().next());
        assertEquals("alice", reloaded.get("key"));
        assertEquals("引号\" 换行\n emoji 😀", reloaded.get("note"));

        Node org = second.getNode(acme.getId());
        assertEquals(List.of("a", "b"), org.get("tags"));
        assertEquals(2, ((Number) org.get("weight")).intValue());
        assertEquals(Boolean.TRUE, org.get("active"));

        Edge edge = second.getEdges(alice.getId()).get(0);
        assertEquals("WORKS_FOR", edge.getType());
        assertEquals(alice.getId(), edge.getStartNodeId());
        assertEquals(acme.getId(), edge.getEndNodeId());
        assertEquals(2020, ((Number) edge.get("since")).intValue());
    }

    @Test
    void replaysUpdatesAndExplicitIds() {
        JsonlGraphStore first = new JsonlGraphStore(file());
        Node node = first.addNode(42, "Thing", Map.of("v", 1));
        assertEquals(42, node.getId());
        first.updateNode(42, Map.of("v", 2, "extra", "keep"));
        Edge edge = first.addEdge(7, "REL", 42, 42, Map.of());
        first.updateEdge(7, Map.of("w", 1.5));

        JsonlGraphStore second = new JsonlGraphStore(file());
        Node reloaded = second.getNode(42);
        assertEquals(2, ((Number) reloaded.get("v")).intValue());
        assertEquals("keep", reloaded.get("extra"));
        assertEquals(1.5, ((Number) second.getEdge(7).get("w")).doubleValue());
        assertEquals(1, second.getNodeCount());
        assertEquals(1, second.getEdgeCount());

        // 新写入接在重放后的 id 高水位之上，不会撞已有 id
        Node fresh = second.addNode("Thing", Map.of());
        assertTrue(fresh.getId() > 42);
        Edge freshEdge = second.addEdge("REL", fresh.getId(), fresh.getId(), Map.of());
        assertTrue(freshEdge.getId() > 7);
    }

    @Test
    void compactsJournalWithoutLosingData() throws Exception {
        JsonlGraphStore store = new JsonlGraphStore(file());
        Node a = store.addNode("T", Map.of("v", 0));
        Node b = store.addNode("T", Map.of("v", 0));
        // 阈值是 max(4096, 2×活跃数)，4000 次 update 足以触发一次压实
        for (int i = 1; i <= 4000; i++) {
            store.updateNode(a.getId(), Map.of("v", i));
        }
        assertTrue(Files.size(file()) < 4000 * 40, "压实后日志不应保留全部历史行");

        JsonlGraphStore reopened = new JsonlGraphStore(file());
        assertEquals(2, reopened.getNodeCount());
        assertEquals(4000, ((Number) reopened.getNode(a.getId()).get("v")).intValue());
        assertEquals(0, ((Number) reopened.getNode(b.getId()).get("v")).intValue());
    }

    @Test
    void skipsCorruptedLines() throws Exception {
        JsonlGraphStore first = new JsonlGraphStore(file());
        first.addNode("T", Map.of("k", "v"));
        first.close();
        Files.writeString(file(), "{\"op\":\"BOGUS\"\n", java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        JsonlGraphStore second = new JsonlGraphStore(file());
        assertEquals(1, second.getNodeCount());
        assertEquals("v", second.getAllNodes().get(0).get("k"));
        // 重放后压实，坏行不再进快照；再重启照常
        JsonlGraphStore third = new JsonlGraphStore(file());
        assertEquals(1, third.getNodeCount());
        assertNull(third.getEdge(999));
    }
}
