package com.zifang.z.ontology.kb;

import com.zifang.z.kb.api.Entity;
import com.zifang.z.kb.api.EntityType;
import com.zifang.z.kb.api.Relation;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.OntologySnapshot;
import com.zifang.z.ontology.api.model.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbOntologyMapperTest {

    private final KbOntologyMapper mapper = new KbOntologyMapper();

    @Test
    void mapsEntityToTypedIndividual() {
        Entity entity = Entity.builder()
                .canonicalName("InfluxDB")
                .displayName("InfluxDB 时序数据库")
                .type(EntityType.DATABASE)
                .description("TSDB")
                .workspace("team-a")
                .frequency(7)
                .importance(0.8)
                .sourceChunkIds(List.of("c1", "c2"))
                .sourceDocumentIds(List.of("doc1"))
                .build();

        Individual individual = mapper.toIndividual(entity);

        assertEquals("InfluxDB", individual.getKey());
        assertEquals("InfluxDB 时序数据库", individual.getLabel());
        assertEquals(List.of("DATABASE"), List.copyOf(individual.getTypes()));
        assertEquals(List.of("doc1", "c1", "c2"), individual.getSources());
        assertEquals("TSDB", individual.getProperties().get("description"));
        assertEquals(7, individual.getProperties().get("frequency"));
    }

    @Test
    void mapsRelationToStatement() {
        Relation relation = Relation.builder()
                .type("USES")
                .sourceEntityName("z-opc")
                .targetEntityName("InfluxDB")
                .weight(2.5)
                .evidenceChunkIds(List.of("c9"))
                .build();

        Statement statement = mapper.toStatement(relation);

        assertEquals("z-opc", statement.getSubject());
        assertEquals("USES", statement.getPredicate());
        assertEquals("InfluxDB", statement.getObject());
        assertEquals(2.5, statement.getWeight());
        assertEquals(List.of("c9"), statement.getSources());
    }

    @Test
    void skipsUnkeyedEntitiesAndBlankRelations() {
        Entity noName = Entity.builder().canonicalName(" ").build();
        Relation blank = Relation.builder().type("USES").sourceEntityName("a").build();

        assertNull(mapper.toIndividual(noName));
        assertNull(mapper.toStatement(blank));

        OntologySnapshot snapshot = mapper.toSnapshot("ws", List.of(noName), List.of(blank));
        assertTrue(snapshot.getIndividuals().isEmpty());
        assertTrue(snapshot.getStatements().isEmpty());
    }
}
