package com.zifang.z.ontology.graph.adapter;

import com.zifang.z.graph.core.InMemoryGraphStore;
import com.zifang.z.ontology.api.exception.OntologyException;
import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.graph.Neighbor;
import com.zifang.z.ontology.api.graph.Subgraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZGraphOntologyPortTest {

    private static final String ONT = "people";

    private InMemoryGraphStore store;
    private ZGraphOntologyPort port;

    @BeforeEach
    void setUp() {
        store = new InMemoryGraphStore();
        port = new ZGraphOntologyPort(store);
    }

    @Test
    void roundTripsNodesAndEdges() {
        port.upsertNode(ONT, "ws", "class:Person", List.of("OntologyClass", "Person"),
                Map.of("name", "Person"));
        port.upsertNode(ONT, "ws", "class:Entity", List.of("OntologyClass", "Entity"),
                Map.of("name", "Entity"));
        port.upsertEdge(ONT, "ws", "sub:Person", "SUB_CLASS_OF", "class:Person", "class:Entity",
                Map.of("inferred", false));

        GraphNode node = port.findNode(ONT, "ws", "class:Person");
        assertNotNull(node);
        assertEquals(List.of("OntologyClass", "Person"), node.getLabels());
        assertEquals("Person", node.getProperties().get("name"));
        assertFalse(node.getProperties().containsKey("labels"));

        List<Neighbor> neighbors = port.neighbors(ONT, "ws", "class:Person", 2, "SUB_CLASS_OF");
        assertEquals(1, neighbors.size());
        assertEquals("class:Entity", neighbors.get(0).getNode().getKey());
        assertEquals("OUT", neighbors.get(0).getDirection());

        List<Neighbor> incoming = port.neighbors(ONT, "ws", "class:Entity", 1, "SUB_CLASS_OF");
        assertEquals("IN", incoming.get(0).getDirection());

        assertEquals(2, port.nodeCount(ONT, "ws"));
        assertEquals(1, port.edgeCount(ONT, "ws"));
    }

    @Test
    void isolatesWorkspacesAndOntologies() {
        port.upsertNode(ONT, "ws-a", "class:Person", List.of("OntologyClass"), Map.of());
        port.upsertNode(ONT, "ws-b", "class:Person", List.of("OntologyClass"), Map.of());
        port.upsertNode("other", "ws-a", "class:Person", List.of("OntologyClass"), Map.of());

        assertEquals(1, port.nodeCount(ONT, "ws-a"));
        assertEquals(1, port.nodeCount(ONT, "ws-b"));
        assertEquals(1, port.nodeCount("other", "ws-a"));

        port.reset(ONT, "ws-a");

        assertEquals(0, port.nodeCount(ONT, "ws-a"));
        assertEquals(1, port.nodeCount(ONT, "ws-b"));
        assertEquals(1, port.nodeCount("other", "ws-a"));
    }

    @Test
    void mergesPropertiesOnRepeatedUpsert() {
        port.upsertNode(ONT, "ws", "individual:alice", List.of("OntologyIndividual"),
                Map.of("name", "Alice", "allTypes", List.of("Person")));
        port.upsertNode(ONT, "ws", "individual:alice", List.of("OntologyIndividual"),
                Map.of("data:age", 35));

        GraphNode node = port.findNode(ONT, "ws", "individual:alice");
        assertEquals("Alice", node.getProperties().get("name"));
        assertEquals(35, node.getProperties().get("data:age"));
        assertEquals(1, port.nodeCount(ONT, "ws"));
    }

    @Test
    void buildsSubgraphAroundSeed() {
        port.upsertNode(ONT, "ws", "individual:alice", List.of("OntologyIndividual"), Map.of());
        port.upsertNode(ONT, "ws", "individual:acme", List.of("OntologyIndividual"), Map.of());
        port.upsertNode(ONT, "ws", "individual:carol", List.of("OntologyIndividual"), Map.of());
        port.upsertEdge(ONT, "ws", "e1", "worksFor", "individual:alice", "individual:acme", Map.of());
        port.upsertEdge(ONT, "ws", "e2", "worksFor", "individual:acme", "individual:carol", Map.of());

        Subgraph depth1 = port.subgraph(ONT, "ws", List.of("individual:alice"), 1);
        assertEquals(2, depth1.getNodes().size());
        assertEquals(1, depth1.getEdges().size());

        Subgraph depth2 = port.subgraph(ONT, "ws", List.of("individual:alice"), 2);
        assertEquals(3, depth2.getNodes().size());
        assertEquals(2, depth2.getEdges().size());
    }

    @Test
    void rejectsEdgesWithoutEndpoints() {
        port.upsertNode(ONT, "ws", "individual:alice", List.of("OntologyIndividual"), Map.of());

        assertThrows(OntologyException.class,
                () -> port.upsertEdge(ONT, "ws", "e1", "worksFor", "individual:alice", "individual:ghost", Map.of()));
    }

    @Test
    void sharesUnderlyingStoreWithHostGraph() {
        port.upsertNode(ONT, "ws", "class:Person", List.of("OntologyClass"), Map.of());
        // 宿主自己往同一个 GraphStore 加节点：reset 不应误删
        store.addNode("HostNode", Map.of("host", true));

        port.reset(ONT, "ws");

        assertEquals(1, store.getNodeCount());
        assertEquals(0, port.nodeCount(ONT, "ws"));
    }

    @Test
    void rebuildsScopeIndexOnNewPortInstance() {
        port.upsertNode(ONT, "ws", "class:Person", List.of("OntologyClass", "Person"), Map.of("name", "Person"));
        port.upsertNode(ONT, "ws", "class:Entity", List.of("OntologyClass", "Entity"), Map.of("name", "Entity"));
        port.upsertEdge(ONT, "ws", "sub:Person", "SUB_CLASS_OF", "class:Person", "class:Entity", Map.of());

        ZGraphOntologyPort restarted = new ZGraphOntologyPort(store);

        assertEquals(2, restarted.nodeCount(ONT, "ws"));
        assertEquals(1, restarted.edgeCount(ONT, "ws"));
        GraphNode node = restarted.findNode(ONT, "ws", "class:Person");
        assertNotNull(node);
        assertEquals(List.of("OntologyClass", "Person"), node.getLabels());
        assertFalse(node.getProperties().containsKey("__ontKey"));

        List<Neighbor> neighbors = restarted.neighbors(ONT, "ws", "class:Person", 1, "SUB_CLASS_OF");
        assertEquals(1, neighbors.size());
        assertEquals("class:Entity", neighbors.get(0).getNode().getKey());
    }

    @Test
    void reportsParallelEdgesInNeighbors() {
        port.upsertNode(ONT, "ws", "individual:alice", List.of("OntologyIndividual"), Map.of());
        port.upsertNode(ONT, "ws", "individual:acme", List.of("OntologyIndividual"), Map.of());
        port.upsertEdge(ONT, "ws", "e1", "worksFor", "individual:alice", "individual:acme", Map.of());
        port.upsertEdge(ONT, "ws", "e2", "mentors", "individual:alice", "individual:acme", Map.of());
        port.upsertEdge(ONT, "ws", "e3", "backedBy", "individual:acme", "individual:alice", Map.of());

        assertEquals(3, port.neighbors(ONT, "ws", "individual:alice", 1, null).size());
        assertEquals(1, port.neighbors(ONT, "ws", "individual:alice", 1, "worksFor").size());
        assertEquals(1, port.neighbors(ONT, "ws", "individual:alice", 1, "backedBy").size());
    }

    @Test
    void atomicBatchIsAllOrNothing() {
        port.upsertNode(ONT, "ws", "class:Person", List.of("OntologyClass"), Map.of("name", "Person"));
        port.upsertEdge(ONT, "ws", "sub:Person", "SUB_CLASS_OF",
                "class:Person", "class:Person", Map.of());

        assertTrue(port.supportsAtomicBatch());
        port.beginAtomicBatch();
        port.reset(ONT, "ws");
        port.upsertNode(ONT, "ws", "class:New", List.of("OntologyClass"), Map.of());
        // 端点缺失在暂存期就报错，不会等到 commit 才留半成品
        assertThrows(OntologyException.class,
                () -> port.upsertEdge(ONT, "ws", "e9", "T", "class:New", "individual:ghost", Map.of()));
        port.rollbackAtomicBatch();

        assertEquals(1, port.nodeCount(ONT, "ws"));
        assertEquals(1, port.edgeCount(ONT, "ws"));
        assertNotNull(port.findNode(ONT, "ws", "class:Person"));

        port.beginAtomicBatch();
        port.reset(ONT, "ws");
        port.upsertNode(ONT, "ws", "class:New", List.of("OntologyClass"), Map.of());
        port.commitAtomicBatch();

        assertEquals(1, port.nodeCount(ONT, "ws"));
        assertNotNull(port.findNode(ONT, "ws", "class:New"));
    }
}
