package com.zifang.z.ontology.core.validation;

import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.core.reasoning.RdfsReasoner;
import com.zifang.z.ontology.core.support.TestSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyValidatorTest {

    private final RdfsReasoner reasoner = new RdfsReasoner();
    private final OntologyValidator validator = new OntologyValidator();

    @Test
    void acceptsConsistentData() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("alice", "Person"),
                TestSchemas.individual("acme", "Organization"));
        List<Statement> statements = List.of(
                TestSchemas.object("alice", "worksFor", "acme"),
                TestSchemas.literal("alice", "age", "35"),
                TestSchemas.literal("alice", "email", "alice@example.com"));

        ValidationReport report = validate(schema, individuals, statements);

        assertTrue(report.isValid(), () -> "unexpected issues: " + report.getIssues());
        assertEquals(0, report.getIssues().size());
    }

    @Test
    void reportsUnknownClassAndProperty() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("x", "Ghost"));
        List<Statement> statements = List.of(TestSchemas.object("x", "nope", "y"));

        ValidationReport report = validate(schema, individuals, statements);

        assertFalse(report.isValid());
        assertTrue(hasCode(report, "UNKNOWN_CLASS"));
        assertTrue(hasCode(report, "UNKNOWN_PROPERTY"));
    }

    @Test
    void reportsDisjointViolation() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("bad", "Person", "Organization"));

        ValidationReport report = validate(schema, individuals, List.of());

        assertTrue(hasCode(report, "DISJOINT_VIOLATION"));
    }

    @Test
    void reportsDomainAndRangeViolations() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("acme", "Organization"),
                TestSchemas.individual("alice", "Person"));
        List<Statement> statements = List.of(TestSchemas.object("acme", "worksFor", "alice"));

        ValidationReport report = validate(schema, individuals, statements);

        assertTrue(hasCode(report, "DOMAIN_VIOLATION"));
        assertTrue(hasCode(report, "RANGE_VIOLATION"));
    }

    @Test
    void reportsFunctionalConflict() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("alice", "Person"));
        List<Statement> statements = List.of(
                TestSchemas.literal("alice", "email", "a@example.com"),
                TestSchemas.literal("alice", "email", "b@example.com"));

        ValidationReport report = validate(schema, individuals, statements);

        assertTrue(hasCode(report, "FUNCTIONAL_CONFLICT"));
    }

    @Test
    void reportsLiteralMismatchAndInvalidLiteral() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("alice", "Person"),
                TestSchemas.individual("acme", "Organization"));
        List<Statement> statements = List.of(
                TestSchemas.literal("alice", "worksFor", "acme"),
                TestSchemas.object("alice", "age", "acme"),
                TestSchemas.literal("alice", "age", "not-a-number"));

        ValidationReport report = validate(schema, individuals, statements);

        assertTrue(hasCode(report, "LITERAL_MISMATCH"));
        assertTrue(hasCode(report, "INVALID_LITERAL"));
    }

    @Test
    void warnsUntypedIndividualsAndUnknownEndpoints() {
        OntologySchema schema = TestSchemas.people();
        List<Individual> individuals = List.of(TestSchemas.individual("alice"));
        List<Statement> statements = List.of(TestSchemas.object("alice", "knows", "bob"));

        ValidationReport report = validate(schema, individuals, statements);

        assertTrue(hasCode(report, "UNTYPED_INDIVIDUAL"));
        assertTrue(hasCode(report, "UNKNOWN_OBJECT"));
        // 只有 WARNING 时仍算有效
        assertTrue(report.isValid());
    }

    private ValidationReport validate(OntologySchema schema, List<Individual> individuals,
                                      List<Statement> statements) {
        return validator.validate(schema, reasoner.infer(schema, individuals, statements));
    }

    private static boolean hasCode(ValidationReport report, String code) {
        return report.getIssues().stream().anyMatch(i -> code.equals(i.getCode()));
    }
}
