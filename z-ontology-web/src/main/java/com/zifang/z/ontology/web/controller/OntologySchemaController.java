package com.zifang.z.ontology.web.controller;

import com.zifang.z.ontology.api.exception.OntologyNotFoundException;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.service.OntologyService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本体 Schema 管理：注册、查询、删除。
 */
@RestController
@RequestMapping("/api/ontology/schemas")
public class OntologySchemaController {

    private final OntologyService service;

    public OntologySchemaController(OntologyService service) {
        this.service = service;
    }

    @GetMapping
    public List<OntologySchema> list() {
        return service.listSchemas();
    }

    @GetMapping("/{ontologyId}")
    public OntologySchema get(@PathVariable String ontologyId) {
        return service.findSchema(ontologyId).orElseThrow(() -> new OntologyNotFoundException(ontologyId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OntologySchema register(@RequestBody OntologySchema schema) {
        return service.registerSchema(schema);
    }

    @DeleteMapping("/{ontologyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String ontologyId) {
        service.deleteSchema(ontologyId);
    }
}
