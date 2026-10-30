package com.zifang.z.ontology.kb;

import com.zifang.z.kb.api.Entity;
import com.zifang.z.kb.api.EntityType;
import com.zifang.z.kb.api.GraphQueryResult;
import com.zifang.z.kb.api.KnowledgeGraphStore;
import com.zifang.z.kb.api.Relation;
import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.model.MaterializationReport;
import com.zifang.z.ontology.api.model.OntologySnapshot;
import com.zifang.z.ontology.api.service.OntologySource;
import com.zifang.z.ontology.core.graph.SimpleOntologyGraphPort;
import com.zifang.z.ontology.core.materialize.OntologyMaterializer;
import com.zifang.z.ontology.core.reasoning.RdfsReasoner;
import com.zifang.z.ontology.core.schema.InMemoryOntologyStore;
import com.zifang.z.ontology.core.service.DefaultOntologyService;
import com.zifang.z.ontology.core.validation.OntologyValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbOntologySourceTest {

    private final FakeKbStore store = new FakeKbStore();
    private final KbOntologySource source = new KbOntologySource(store);

    @Test
    void loadsEntitiesAndDeduplicatesRelations() {
        OntologySnapshot snapshot = source.load("ws");

        assertEquals("kb", source.id());
        assertEquals(3, snapshot.getIndividuals().size());
        assertEquals(2, snapshot.getStatements().size());
        assertTrue(snapshot.getStatements().stream()
                .allMatch(s -> "BELONGS_TO".equals(s.getPredicate())));
    }

    @Test
    void drivesEndToEndMaterializationWithGeneratedSchema() {
        DefaultOntologyService service = new DefaultOntologyService(new InMemoryOntologyStore(),
                new RdfsReasoner(), new OntologyValidator(), new OntologyMaterializer(),
                new SimpleOntologyGraphPort(), List.<OntologySource>of(source));
        service.registerSchema(new KbSchemaFactory().create());

        MaterializationReport report = service.materialize(KbSchemaFactory.SCHEMA_ID, "ws", KbOntologySource.SOURCE_ID);

        assertTrue(report.getValidation().isValid(), () -> "issues: " + report.getValidation().getIssues());
        assertEquals(3, report.getIndividualNodes());
        List<GraphNode> people = service.instances(KbSchemaFactory.SCHEMA_ID, "ws", EntityType.PERSON.name());
        assertEquals(List.of("individual:alice", "individual:bob"),
                people.stream().map(GraphNode::getKey).sorted().toList());
        assertTrue(service.individualNodes(KbSchemaFactory.SCHEMA_ID, "ws").stream()
                .allMatch(n -> ((List<?>) n.getProperties().get("allTypes")).contains("Entity")));
    }

    /** 只实现本测试用到的读接口，其余一律不支持。 */
    private static final class FakeKbStore implements KnowledgeGraphStore {

        private final List<Entity> entities = List.of(
                Entity.builder().canonicalName("alice").displayName("Alice")
                        .type(EntityType.PERSON).workspace("ws").build(),
                Entity.builder().canonicalName("bob").displayName("Bob")
                        .type(EntityType.PERSON).workspace("ws").build(),
                Entity.builder().canonicalName("acme").displayName("Acme")
                        .type(EntityType.ORGANIZATION).workspace("ws").build());

        private final Relation aliceToAcme = Relation.builder().id("r1").type("BELONGS_TO")
                .sourceEntityName("alice").targetEntityName("acme").build();
        private final Relation bobToAcme = Relation.builder().id("r2").type("BELONGS_TO")
                .sourceEntityName("bob").targetEntityName("acme").build();

        @Override
        public List<Entity> listAllEntities(String workspace, int limit) {
            return "ws".equals(workspace) ? entities : List.of();
        }

        @Override
        public List<Relation> getOutRelations(String workspace, String entityName) {
            switch (entityName) {
                case "alice":
                    return List.of(aliceToAcme);
                case "bob":
                    return List.of(bobToAcme);
                case "acme":
                    // acme 的出边里重复出现 r1：来源必须去重
                    return List.of(aliceToAcme);
                default:
                    return List.of();
            }
        }

        @Override public Entity upsertEntity(Entity entity) { throw new UnsupportedOperationException(); }
        @Override public List<Entity> upsertEntities(List<Entity> list) { throw new UnsupportedOperationException(); }
        @Override public Entity findEntity(String workspace, String canonicalName) { throw new UnsupportedOperationException(); }
        @Override public Entity getEntity(String workspace, String id) { throw new UnsupportedOperationException(); }
        @Override public List<Entity> searchEntities(String workspace, String keyword, int limit) { throw new UnsupportedOperationException(); }
        @Override public List<Entity> listEntitiesByType(String workspace, EntityType type, int limit) { throw new UnsupportedOperationException(); }
        @Override public Relation upsertRelation(Relation relation) { throw new UnsupportedOperationException(); }
        @Override public List<Relation> upsertRelations(List<Relation> list) { throw new UnsupportedOperationException(); }
        @Override public List<Relation> getInRelations(String workspace, String entityName) { throw new UnsupportedOperationException(); }
        @Override public Set<Entity> traverse(String workspace, String entityName, int maxDepth) { throw new UnsupportedOperationException(); }
        @Override public GraphQueryResult subgraph(String workspace, List<String> entityNames, int depth) { throw new UnsupportedOperationException(); }
        @Override public boolean deleteEntity(String workspace, String entityName) { throw new UnsupportedOperationException(); }
        @Override public int deleteByDocument(String workspace, String documentId) { throw new UnsupportedOperationException(); }
        @Override public Map<String, Long> stats(String workspace) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }
}
