package com.zifang.z.ontology.core.service;

import com.zifang.z.ontology.api.exception.InvalidOntologySchemaException;
import com.zifang.z.ontology.api.exception.OntologyException;
import com.zifang.z.ontology.api.exception.OntologyNotFoundException;
import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.graph.Subgraph;
import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.MaterializationReport;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.OntologySnapshot;
import com.zifang.z.ontology.api.model.OntologyStats;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.api.service.OntologySource;
import com.zifang.z.ontology.core.graph.SimpleOntologyGraphPort;
import com.zifang.z.ontology.core.materialize.OntologyMaterializer;
import com.zifang.z.ontology.core.reasoning.RdfsReasoner;
import com.zifang.z.ontology.core.schema.InMemoryOntologyStore;
import com.zifang.z.ontology.core.support.TestSchemas;
import com.zifang.z.ontology.core.validation.OntologyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultOntologyServiceTest {

    private static final String WS = "team-a";

    private InMemoryOntologyStore store;
    private DefaultOntologyService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryOntologyStore();
        service = new DefaultOntologyService(store, new RdfsReasoner(), new OntologyValidator(),
                new OntologyMaterializer(), new SimpleOntologyGraphPort());
    }

    @Test
    void registersAndFindsSchema() {
        OntologySchema schema = service.registerSchema(TestSchemas.people());

        assertTrue(schema.getCreatedAt() != null);
        assertTrue(schema.getUpdatedAt() != null);
        assertTrue(service.findSchema("people").isPresent());
        assertEquals(1, service.listSchemas().size());
        assertTrue(service.deleteSchema("people"));
        assertFalse(service.findSchema("people").isPresent());
    }

    @Test
    void rejectsInvalidSchemaWithReport() {
        OntologySchema schema = new OntologySchema("broken", "Broken");
        com.zifang.z.ontology.api.model.OntologyClass c = new com.zifang.z.ontology.api.model.OntologyClass();
        c.setName("A");
        c.getSuperClasses().add("Missing");
        schema.getClasses().add(c);

        InvalidOntologySchemaException ex = assertThrows(InvalidOntologySchemaException.class,
                () -> service.registerSchema(schema));

        assertEquals(1, ex.getReport().getErrorCount());
        assertTrue(store.find("broken").isEmpty());
    }

    @Test
    void materializesInlineDataAndAnswersQueries() {
        service.registerSchema(TestSchemas.people());

        MaterializationReport report = service.materializeData("people", WS,
                List.of(TestSchemas.individual("alice", "Person"),
                        TestSchemas.individual("acme", "Organization")),
                List.of(TestSchemas.object("alice", "worksFor", "acme")));

        assertTrue(report.getValidation().isValid());
        assertEquals(2, report.getIndividualNodes());

        List<GraphNode> classes = service.classNodes("people", WS);
        assertEquals(4, classes.size());
        List<GraphNode> individuals = service.individualNodes("people", WS);
        assertEquals(2, individuals.size());
        assertEquals(List.of("individual:alice"),
                service.instances("people", WS, "Person").stream().map(GraphNode::getKey).toList());

        Subgraph subgraph = service.subgraph("people", WS, List.of("individual:alice"), 1);
        assertEquals(4, subgraph.getNodes().size());
        assertTrue(subgraph.getNodes().stream().anyMatch(n -> "individual:acme".equals(n.getKey())));
        assertTrue(subgraph.getEdges().stream().anyMatch(e -> "worksFor".equals(e.getType())));

        OntologyStats stats = service.stats("people", WS);
        assertEquals(4, stats.getClassNodes());
        assertEquals(2, stats.getIndividualNodes());
        assertEquals(1, stats.getExplicitStatements());
        assertTrue(stats.getNodeCount() >= 6);
        assertTrue(stats.getEdgeCount() >= 5);
        assertTrue(stats.getLastMaterializedAt() != null);
    }

    @Test
    void validatesRegisteredSource() {
        service.registerSource(new FixedSource());
        service.registerSchema(TestSchemas.people());

        ValidationReport report = service.validateSource("people", WS, "fixed");
        assertEquals(0, report.getErrorCount());

        MaterializationReport materialized = service.materialize("people", WS, "fixed");
        assertEquals("fixed", materialized.getSourceId());
    }

    @Test
    void failsForUnknownOntologySourceAndId() {
        service.registerSchema(TestSchemas.people());

        assertThrows(OntologyNotFoundException.class, () -> service.validate("nope", List.of(), List.of()));
        assertThrows(OntologyException.class, () -> service.materialize("people", WS, "nope"));
    }

    @Test
    void infersAndValidatesWithoutMaterializing() {
        service.registerSchema(TestSchemas.people());
        List<Individual> individuals = List.of(TestSchemas.individual("alice", "Person"));
        // employedBy 是 worksFor 的子属性，推理应扩展出 worksFor 断言
        List<Statement> statements = List.of(TestSchemas.object("alice", "employedBy", "acme"));

        InferenceResult inference = service.infer("people", individuals, statements);

        assertTrue(inference.getStatements().stream()
                .anyMatch(s -> s.isInferred() && "worksFor".equals(s.getPredicate())));
    }

    private static final class FixedSource implements OntologySource {
        @Override
        public String id() {
            return "fixed";
        }

        @Override
        public String description() {
            return "fixed test source";
        }

        @Override
        public OntologySnapshot load(String workspace) {
            return new OntologySnapshot(workspace,
                    List.of(TestSchemas.individual("alice", "Person"),
                            TestSchemas.individual("acme", "Organization")),
                    List.of(TestSchemas.object("alice", "worksFor", "acme")));
        }
    }
}
