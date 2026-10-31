package com.zifang.z.ontology.core.graph;

import com.zifang.z.ontology.api.graph.Neighbor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleOntologyGraphPortTest {

    private static final String ONT = "people";
    private static final String WS = "ws";

    private final SimpleOntologyGraphPort port = new SimpleOntologyGraphPort();

    @Test
    void reportsParallelEdgesInNeighbors() {
        port.upsertNode(ONT, WS, "individual:alice", List.of("OntologyIndividual"), Map.of());
        port.upsertNode(ONT, WS, "individual:acme", List.of("OntologyIndividual"), Map.of());
        port.upsertEdge(ONT, WS, "e1", "worksFor", "individual:alice", "individual:acme", Map.of());
        port.upsertEdge(ONT, WS, "e2", "mentors", "individual:alice", "individual:acme", Map.of());
        port.upsertEdge(ONT, WS, "e3", "backedBy", "individual:acme", "individual:alice", Map.of());

        List<Neighbor> all = port.neighbors(ONT, WS, "individual:alice", 1, null);
        assertEquals(3, all.size());

        List<Neighbor> worksFor = port.neighbors(ONT, WS, "individual:alice", 1, "worksFor");
        assertEquals(1, worksFor.size());
        assertEquals("individual:acme", worksFor.get(0).getNode().getKey());
        assertEquals("OUT", worksFor.get(0).getDirection());

        List<Neighbor> backedBy = port.neighbors(ONT, WS, "individual:alice", 1, "backedBy");
        assertEquals(1, backedBy.size());
        assertEquals("IN", backedBy.get(0).getDirection());
    }

    @Test
    void atomicBatchIsAllOrNothing() {
        port.upsertNode(ONT, WS, "class:Person", List.of("OntologyClass"), Map.of());
        long nodesBefore = port.nodeCount(ONT, WS);

        port.beginAtomicBatch();
        port.reset(ONT, WS);
        port.upsertNode(ONT, WS, "class:New", List.of("OntologyClass"), Map.of());

        // 未提交前旧数据照常可见
        assertEquals(nodesBefore, port.nodeCount(ONT, WS));
        assertNotNull(port.findNode(ONT, WS, "class:Person"));

        port.rollbackAtomicBatch();

        assertEquals(nodesBefore, port.nodeCount(ONT, WS));
        assertNotNull(port.findNode(ONT, WS, "class:Person"));

        port.beginAtomicBatch();
        port.reset(ONT, WS);
        port.upsertNode(ONT, WS, "class:New", List.of("OntologyClass"), Map.of());
        port.commitAtomicBatch();

        assertEquals(1, port.nodeCount(ONT, WS));
        assertNotNull(port.findNode(ONT, WS, "class:New"));
    }

    @Test
    void rejectsNestedBatch() {
        port.beginAtomicBatch();
        assertThrows(IllegalStateException.class, () -> port.beginAtomicBatch());
        port.rollbackAtomicBatch();
        assertTrue(port.supportsAtomicBatch());
    }
}
