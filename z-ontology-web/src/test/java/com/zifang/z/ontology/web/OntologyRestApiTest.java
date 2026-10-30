package com.zifang.z.ontology.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import com.zifang.z.ontology.web.testhost.OntologyWebTestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = OntologyWebTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "zont.storage-path=${java.io.tmpdir}/zont-web-test-${random.uuid}",
        "springdoc.api-docs.enabled=false"
})
class OntologyRestApiTest {

    private static final String SCHEMA = """
            {
              "id": "people",
              "name": "People",
              "classes": [
                {"name": "Entity"},
                {"name": "Person", "superClasses": ["Entity"]},
                {"name": "Organization", "superClasses": ["Entity"], "disjointWith": ["Person"]}
              ],
              "properties": [
                {"name": "worksFor", "kind": "OBJECT", "domain": "Person", "range": "Organization"}
              ]
            }
            """;

    private static final String DATA = """
            {
              "workspace": "team",
              "individuals": [
                {"key": "alice", "name": "Alice", "types": ["Person"]},
                {"key": "acme", "name": "Acme", "types": ["Organization"]}
              ],
              "statements": [
                {"subject": "alice", "predicate": "worksFor", "object": "acme"}
              ]
            }
            """;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void registersSchemaMaterializesAndQueries() throws Exception {
        ResponseEntity<String> created = post("/api/ontology/schemas", SCHEMA);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        ResponseEntity<String> materialized = post("/api/ontology/people/materialize-inline?workspace=team", DATA);
        assertEquals(HttpStatus.OK, materialized.getStatusCode());
        JsonNode report = json.readTree(materialized.getBody());
        assertEquals(2, report.path("individualNodes").asInt());
        assertTrue(report.path("validation").path("valid").asBoolean());

        JsonNode instances = json.readTree(rest.getForObject(
                "/api/ontology/people/instances?workspace=team&className=Person", String.class));
        assertEquals(1, instances.size());
        assertEquals("individual:alice", instances.get(0).path("key").asText());

        JsonNode stats = json.readTree(rest.getForObject(
                "/api/ontology/people/stats?workspace=team", String.class));
        assertEquals(3, stats.path("classNodes").asInt());
        assertTrue(stats.path("edgeCount").asInt() >= 4);

        JsonNode neighbors = json.readTree(rest.getForObject(
                "/api/ontology/people/neighbors?workspace=team&key=individual:alice&depth=1&edgeType=worksFor",
                String.class));
        assertEquals(1, neighbors.size());
        assertEquals("individual:acme", neighbors.get(0).path("node").path("key").asText());
    }

    @Test
    void returns404ForUnknownOntology() {
        ResponseEntity<String> response = rest.getForEntity("/api/ontology/ghost/classes", String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void returns400WithReportForInvalidSchema() throws Exception {
        ResponseEntity<String> response = post("/api/ontology/schemas",
                "{\"id\":\"bad\",\"name\":\"Bad\",\"classes\":[{\"name\":\"A\",\"superClasses\":[\"Missing\"]}]}");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        JsonNode body = json.readTree(response.getBody());
        assertEquals("INVALID_ONTOLOGY_SCHEMA", body.path("error").asText());
        assertNotNull(body.path("validation").path("issues"));
        assertEquals("UNKNOWN_SUPER_CLASS", body.path("validation").path("issues").get(0).path("code").asText());
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
