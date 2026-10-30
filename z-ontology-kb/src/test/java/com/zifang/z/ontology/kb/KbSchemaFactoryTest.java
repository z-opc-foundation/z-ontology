package com.zifang.z.ontology.kb;

import com.zifang.z.kb.api.EntityType;
import com.zifang.z.kb.api.RelationType;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.core.schema.OntologySchemaValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbSchemaFactoryTest {

    private final KbSchemaFactory factory = new KbSchemaFactory();

    @Test
    void buildsOneClassPerEntityTypeUnderRoot() {
        OntologySchema schema = factory.create();

        assertEquals(KbSchemaFactory.SCHEMA_ID, schema.getId());
        assertEquals(EntityType.values().length + 1, schema.getClasses().size());
        assertEquals(KbSchemaFactory.ROOT_CLASS, schema.findClass(EntityType.PERSON.name()).getSuperClasses().get(0));
        assertEquals(RelationType.values().length, schema.getProperties().size());
    }

    @Test
    void generatedSchemaPassesSchemaValidation() {
        ValidationReport report = new OntologySchemaValidator().validate(factory.create());

        assertTrue(report.isValid(), () -> "schema issues: " + report.getIssues());
    }
}
