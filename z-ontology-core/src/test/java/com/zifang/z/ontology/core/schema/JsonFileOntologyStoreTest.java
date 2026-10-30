package com.zifang.z.ontology.core.schema;

import com.zifang.z.ontology.api.model.OntologyClass;
import com.zifang.z.ontology.api.model.OntologySchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileOntologyStoreTest {

    @TempDir
    Path dir;

    @Test
    void savesAndFindsSchema() {
        JsonFileOntologyStore store = new JsonFileOntologyStore(dir);
        OntologySchema schema = new OntologySchema("people", "People");
        OntologyClass person = new OntologyClass();
        person.setName("Person");
        schema.getClasses().add(person);

        store.save(schema);

        OntologySchema loaded = store.find("people").orElseThrow();
        assertEquals("People", loaded.getName());
        assertEquals("Person", loaded.getClasses().get(0).getName());
        assertTrue(Files.exists(dir.resolve("cGVvcGxl.json")));
    }

    @Test
    void dataSurvivesNewStoreInstance() {
        new JsonFileOntologyStore(dir).save(new OntologySchema("a", "A"));

        assertTrue(new JsonFileOntologyStore(dir).find("a").isPresent());
    }

    @Test
    void listsAllAndSkipsCorruptFiles() throws IOException {
        JsonFileOntologyStore store = new JsonFileOntologyStore(dir);
        store.save(new OntologySchema("a", "A"));
        store.save(new OntologySchema("b", "B"));
        Files.write(dir.resolve("broken.json"), "{not-json".getBytes(StandardCharsets.UTF_8));

        List<OntologySchema> schemas = store.list();

        assertEquals(2, schemas.size());
        assertEquals(List.of("a", "b"), schemas.stream().map(OntologySchema::getId).toList());
    }

    @Test
    void deletesSchema() {
        JsonFileOntologyStore store = new JsonFileOntologyStore(dir);
        store.save(new OntologySchema("a", "A"));

        assertTrue(store.delete("a"));
        assertFalse(store.find("a").isPresent());
        assertFalse(store.delete("a"));
    }

    @Test
    void keepsTraversalIdsInsideDirectory() throws IOException {
        JsonFileOntologyStore store = new JsonFileOntologyStore(dir.resolve("schemas"));

        store.save(new OntologySchema("../../evil", "Evil"));

        assertTrue(store.find("../../evil").isPresent());
        try (Stream<Path> files = Files.walk(dir)) {
            assertTrue(files.filter(Files::isRegularFile)
                    .allMatch(p -> p.startsWith(dir.resolve("schemas"))));
        }
    }
}
