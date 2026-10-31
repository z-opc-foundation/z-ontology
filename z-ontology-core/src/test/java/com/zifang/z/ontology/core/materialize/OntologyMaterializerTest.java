package com.zifang.z.ontology.core.materialize;

import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.graph.Neighbor;
import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.MaterializationReport;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.core.graph.SimpleOntologyGraphPort;
import com.zifang.z.ontology.core.reasoning.RdfsReasoner;
import com.zifang.z.ontology.core.support.TestSchemas;
import com.zifang.z.ontology.core.validation.OntologyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyMaterializerTest {

    private static final String ONT = "people";
    private static final String WS = "team-a";

    private final RdfsReasoner reasoner = new RdfsReasoner();
    private final OntologyValidator validator = new OntologyValidator();
    private final OntologyMaterializer materializer = new OntologyMaterializer();
    private SimpleOntologyGraphPort port;

    @BeforeEach
    void setUp() {
        port = new SimpleOntologyGraphPort();
    }

    @Test
    void materializesClassesInstancesAndPropertyEdges() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("alice", "Person"),
                TestSchemas.individual("acme", "Organization"));
        List<Statement> statements = List.of(
                TestSchemas.object("alice", "worksFor", "acme"),
                TestSchemas.literal("alice", "age", "35"));

        MaterializationReport report = materialize(schema, individuals, statements);

        assertEquals(4, report.getClassNodes());
        assertEquals(2, report.getIndividualNodes());
        assertTrue(report.getValidation().isValid());

        GraphNode classNode = port.findNode(ONT, WS, "class:Person");
        assertNotNull(classNode);
        assertTrue(classNode.getLabels().contains("OntologyClass"));
        assertTrue(classNode.getLabels().contains("Person"));

        GraphNode alice = port.findNode(ONT, WS, "individual:alice");
        assertNotNull(alice);
        assertTrue(alice.getLabels().contains("OntologyIndividual"));
        assertEquals(35, alice.getProperties().get("data:age"));

        Neighbor worksFor = port.neighbors(ONT, WS, "individual:alice", 1, "worksFor").get(0);
        assertEquals("individual:acme", worksFor.getNode().getKey());
        assertEquals("OUT", worksFor.getDirection());
    }

    @Test
    void writesExplicitAndInferredInstanceEdges() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("alice", "Person"));

        materialize(schema, individuals, List.of());

        List<Neighbor> instanceEdges = port.neighbors(ONT, WS, "individual:alice", 1, "INSTANCE_OF");
        assertEquals(2, instanceEdges.size());
        assertTrue(instanceEdges.stream().anyMatch(n -> "class:Person".equals(n.getNode().getKey())
                && Boolean.FALSE.equals(n.getEdge().getProperties().get("inferred"))));
        assertTrue(instanceEdges.stream().anyMatch(n -> "class:Entity".equals(n.getNode().getKey())
                && Boolean.TRUE.equals(n.getEdge().getProperties().get("inferred"))));
    }

    @Test
    void createsPlaceholderForUndeclaredObject() {
        OntologySchema schema = TestSchemas.people();

        materialize(schema, List.of(TestSchemas.individual("alice", "Person")),
                List.of(TestSchemas.object("alice", "knows", "bob")));

        GraphNode bob = port.findNode(ONT, WS, "individual:bob");
        assertNotNull(bob);
        assertEquals(Boolean.TRUE, bob.getProperties().get("placeholder"));
    }

    @Test
    void rematerializationDoesNotDuplicateNodes() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("alice", "Person"));

        MaterializationReport first = materialize(schema, individuals, List.of());
        long nodesAfterFirst = port.nodeCount(ONT, WS);
        long edgesAfterFirst = port.edgeCount(ONT, WS);
        MaterializationReport second = materialize(schema, individuals, List.of());

        assertEquals(nodesAfterFirst, port.nodeCount(ONT, WS));
        assertEquals(edgesAfterFirst, port.edgeCount(ONT, WS));
        assertEquals(first.getEdges(), second.getEdges());
    }

    @Test
    void leavesPreviousMaterializationIntactWhenWriteFailsMidway() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("alice", "Person"),
                TestSchemas.individual("acme", "Organization"));
        List<Statement> statements = List.of(TestSchemas.object("alice", "worksFor", "acme"));

        BombPort failing = new BombPort();
        InferenceResult inference = reasoner.infer(schema, individuals, statements);
        ValidationReport validation = validator.validate(schema, inference);
        materializer.materialize(schema, WS, "test", inference, validation, failing);
        long nodesBefore = failing.nodeCount(ONT, WS);
        long edgesBefore = failing.edgeCount(ONT, WS);
        failing.armed = true;

        assertThrows(IllegalStateException.class,
                () -> materializer.materialize(schema, WS, "test", inference, validation, failing));

        assertEquals(nodesBefore, failing.nodeCount(ONT, WS));
        assertEquals(edgesBefore, failing.edgeCount(ONT, WS));
        assertNotNull(failing.findNode(ONT, WS, "individual:alice"));
    }

    /** 在第 N 次 upsertEdge 时爆炸，模拟物化中途失败。 */
    private static final class BombPort extends SimpleOntologyGraphPort {
        private boolean armed;

        @Override
        public synchronized void upsertEdge(String ontologyId, String workspace, String key, String type,
                                            String fromKey, String toKey, java.util.Map<String, Object> properties) {
            if (armed) {
                throw new IllegalStateException("boom");
            }
            super.upsertEdge(ontologyId, workspace, key, type, fromKey, toKey, properties);
        }
    }

    private MaterializationReport materialize(OntologySchema schema, List<Individual> individuals,
                                              List<Statement> statements) {
        InferenceResult inference = reasoner.infer(schema, individuals, statements);
        ValidationReport validation = validator.validate(schema, inference);
        return materializer.materialize(schema, WS, "test", inference, validation, port);
    }
}
