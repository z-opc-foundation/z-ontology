package com.zifang.z.ontology.bootstrap;

import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.MaterializationReport;
import com.zifang.z.ontology.api.service.OntologyGraphPort;
import com.zifang.z.ontology.api.service.OntologyService;
import com.zifang.z.ontology.graph.adapter.ZGraphOntologyPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = ZOntologyBootstrapApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "zont.storage-path=${java.io.tmpdir}/zont-boot-test-${random.uuid}")
class ZOntologyBootstrapApplicationTest {

    @Autowired
    private OntologyService service;

    @Autowired
    private OntologyGraphPort graphPort;

    @Test
    void wiresKbSchemaAndZGraphPort() {
        assertTrue(service.findSchema("kb").isPresent());
        assertInstanceOf(ZGraphOntologyPort.class, graphPort);
    }

    @Test
    void materializesKbOntologyThroughBootstrap() {
        Individual alice = new Individual("alice", "Alice");
        alice.addType("PERSON");

        MaterializationReport report = service.materializeData("kb", "boot", List.of(alice), List.of());

        assertEquals(1, report.getIndividualNodes());
        List<GraphNode> people = service.instances("kb", "boot", "PERSON");
        assertEquals(List.of("individual:alice"), people.stream().map(GraphNode::getKey).toList());
        assertTrue(service.individualNodes("kb", "boot").get(0).getProperties().get("allTypes")
                instanceof List<?>);
    }
}
