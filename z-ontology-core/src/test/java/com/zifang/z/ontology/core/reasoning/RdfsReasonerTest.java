package com.zifang.z.ontology.core.reasoning;

import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.core.support.TestSchemas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdfsReasonerTest {

    private final RdfsReasoner reasoner = new RdfsReasoner();

    @Test
    void infersSuperClassTypes() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> in = List.of(TestSchemas.individual("alice", "Person"));

        InferenceResult result = reasoner.infer(schema, in, List.of());

        Individual alice = individual(result, "alice");
        assertEquals(1, result.getExplicitTypes());
        assertTrue(alice.getInferredTypes().contains("Entity"));
        assertTrue(alice.allTypes().containsAll(List.of("Person", "Entity")));
    }

    @Test
    void expandsSubPropertyOfStatements() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> in = List.of(TestSchemas.individual("alice", "Person"),
                TestSchemas.individual("acme", "Organization"));

        InferenceResult result = reasoner.infer(schema, in,
                List.of(TestSchemas.object("alice", "employedBy", "acme")));

        Statement worksFor = statement(result, "alice", "worksFor", "acme");
        assertTrue(worksFor.isInferred());
        assertFalse(statement(result, "alice", "employedBy", "acme").isInferred());
        assertEquals(1, result.getInferredStatements());
        assertEquals(1, result.getExplicitStatements());
    }

    @Test
    void infersTypesFromDomainAndRange() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> in = List.of(TestSchemas.individual("alice"), TestSchemas.individual("acme"));

        InferenceResult result = reasoner.infer(schema, in,
                List.of(TestSchemas.object("alice", "worksFor", "acme")));

        assertTrue(individual(result, "alice").getInferredTypes().contains("Person"));
        assertTrue(individual(result, "acme").getInferredTypes().contains("Organization"));
        assertTrue(individual(result, "alice").allTypes().contains("Entity"));
    }

    @Test
    void addsInverseAndSymmetricStatements() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> in = List.of(TestSchemas.individual("alice", "Person"),
                TestSchemas.individual("bob", "Person"),
                TestSchemas.individual("carol", "Person"));

        InferenceResult result = reasoner.infer(schema, in, List.of(
                TestSchemas.object("alice", "spouseOf", "bob"),
                TestSchemas.object("alice", "knows", "carol")));

        assertTrue(statement(result, "bob", "marriedTo", "alice").isInferred());
        assertTrue(statement(result, "carol", "knows", "alice").isInferred());
    }

    @Test
    void computesTransitiveClosure() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> in = List.of(TestSchemas.individual("a", "Employee"),
                TestSchemas.individual("b", "Employee"),
                TestSchemas.individual("c", "Employee"));

        InferenceResult result = reasoner.infer(schema, in, List.of(
                TestSchemas.object("a", "reportsTo", "b"),
                TestSchemas.object("b", "reportsTo", "c")));

        assertTrue(statement(result, "a", "reportsTo", "c").isInferred());
    }

    @Test
    void doesNotInventUnknownSubjects() {
        OntologySchema schema = TestSchemas.people();
        InferenceResult result = reasoner.infer(schema, List.of(TestSchemas.individual("alice", "Person")),
                List.of(TestSchemas.object("ghost", "worksFor", "acme")));

        assertEquals(1, result.getIndividuals().size());
        assertEquals("alice", result.getIndividuals().get(0).getKey());
    }

    @Test
    void computesLongChainClosureWithoutNonConvergence() {
        OntologySchema schema = TestSchemas.people();
        int size = 300;
        List<Individual> in = new ArrayList<>();
        List<Statement> edges = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            in.add(TestSchemas.individual("e" + i, "Employee"));
        }
        for (int i = 0; i + 1 < size; i++) {
            edges.add(TestSchemas.object("e" + i, "reportsTo", "e" + (i + 1)));
        }

        InferenceResult result = reasoner.infer(schema, in, edges);

        assertEquals(size * (size - 1) / 2 - (size - 1), result.getInferredStatements());
        assertTrue(statement(result, "e0", "reportsTo", "e" + (size - 1)).isInferred());
    }

    @Test
    void transitiveClosureKeepsWidestPathWeight() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> in = List.of(TestSchemas.individual("a", "Employee"),
                TestSchemas.individual("b", "Employee"),
                TestSchemas.individual("c", "Employee"),
                TestSchemas.individual("d", "Employee"));
        Statement ab = TestSchemas.object("a", "reportsTo", "b");
        ab.setWeight(0.9);
        Statement bd = TestSchemas.object("b", "reportsTo", "d");
        bd.setWeight(0.3);
        Statement ac = TestSchemas.object("a", "reportsTo", "c");
        ac.setWeight(0.8);
        Statement cd = TestSchemas.object("c", "reportsTo", "d");
        cd.setWeight(0.6);

        InferenceResult result = reasoner.infer(schema, in, List.of(ab, bd, ac, cd));

        assertEquals(0.6, statement(result, "a", "reportsTo", "d").getWeight(), 1e-9);
        assertEquals(0.3, statement(result, "b", "reportsTo", "d").getWeight(), 1e-9);
        assertEquals(0.8, statement(result, "a", "reportsTo", "c").getWeight(), 1e-9);
    }

    private static Individual individual(InferenceResult result, String key) {
        return result.getIndividuals().stream()
                .filter(i -> key.equals(i.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("individual not found: " + key));
    }

    private static Statement statement(InferenceResult result, String s, String p, String o) {
        return result.getStatements().stream()
                .filter(st -> s.equals(st.getSubject()) && p.equals(st.getPredicate()) && o.equals(st.getObject()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("statement not found: " + s + " " + p + " " + o));
    }
}
