package com.zifang.z.ontology.web.controller;

import com.zifang.z.ontology.api.graph.GraphNode;
import com.zifang.z.ontology.api.graph.Neighbor;
import com.zifang.z.ontology.api.graph.Subgraph;
import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.MaterializationReport;
import com.zifang.z.ontology.api.model.OntologySnapshot;
import com.zifang.z.ontology.api.model.OntologyStats;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.api.service.OntologyService;
import com.zifang.z.ontology.api.service.OntologySource;
import com.zifang.z.ontology.starter.config.OntologyProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本体运行时：推理 / 校验 / 物化 / 图查询。
 */
@RestController
@RequestMapping("/api/ontology")
public class OntologyRuntimeController {

    private final OntologyService service;
    private final OntologyProperties properties;

    public OntologyRuntimeController(OntologyService service, OntologyProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/sources")
    public List<SourceView> sources() {
        return service.sources().stream().map(SourceView::of).toList();
    }

    @PostMapping("/{ontologyId}/infer")
    public InferenceResult infer(@PathVariable String ontologyId, @RequestBody OntologySnapshot snapshot) {
        return service.infer(ontologyId, snapshot.getIndividuals(), snapshot.getStatements());
    }

    @PostMapping("/{ontologyId}/validate")
    public ValidationReport validate(@PathVariable String ontologyId, @RequestBody OntologySnapshot snapshot) {
        return service.validate(ontologyId, snapshot.getIndividuals(), snapshot.getStatements());
    }

    @GetMapping("/{ontologyId}/validate-source")
    public ValidationReport validateSource(@PathVariable String ontologyId,
                                           @RequestParam(required = false) String workspace,
                                           @RequestParam String source) {
        return service.validateSource(ontologyId, workspace(workspace), source);
    }

    @PostMapping("/{ontologyId}/materialize")
    public MaterializationReport materialize(@PathVariable String ontologyId,
                                             @RequestParam(required = false) String workspace,
                                             @RequestParam String source) {
        return service.materialize(ontologyId, workspace(workspace), source);
    }

    @PostMapping("/{ontologyId}/materialize-inline")
    public MaterializationReport materializeInline(@PathVariable String ontologyId,
                                                   @RequestParam(required = false) String workspace,
                                                   @RequestBody OntologySnapshot snapshot) {
        String ws = workspace == null ? workspace(snapshot.getWorkspace()) : workspace(workspace);
        return service.materializeData(ontologyId, ws, snapshot.getIndividuals(), snapshot.getStatements());
    }

    @GetMapping("/{ontologyId}/classes")
    public List<GraphNode> classes(@PathVariable String ontologyId,
                                   @RequestParam(required = false) String workspace) {
        return service.classNodes(ontologyId, workspace(workspace));
    }

    @GetMapping("/{ontologyId}/individuals")
    public List<GraphNode> individuals(@PathVariable String ontologyId,
                                       @RequestParam(required = false) String workspace) {
        return service.individualNodes(ontologyId, workspace(workspace));
    }

    @GetMapping("/{ontologyId}/instances")
    public List<GraphNode> instances(@PathVariable String ontologyId,
                                     @RequestParam(required = false) String workspace,
                                     @RequestParam String className) {
        return service.instances(ontologyId, workspace(workspace), className);
    }

    @GetMapping("/{ontologyId}/neighbors")
    public List<Neighbor> neighbors(@PathVariable String ontologyId,
                                    @RequestParam(required = false) String workspace,
                                    @RequestParam String key,
                                    @RequestParam(defaultValue = "1") int depth,
                                    @RequestParam(required = false) String edgeType) {
        return service.neighbors(ontologyId, workspace(workspace), key, depth, edgeType);
    }

    @GetMapping("/{ontologyId}/subgraph")
    public Subgraph subgraph(@PathVariable String ontologyId,
                             @RequestParam(required = false) String workspace,
                             @RequestParam List<String> keys,
                             @RequestParam(defaultValue = "1") int depth) {
        return service.subgraph(ontologyId, workspace(workspace), keys, depth);
    }

    @GetMapping("/{ontologyId}/stats")
    public OntologyStats stats(@PathVariable String ontologyId,
                               @RequestParam(required = false) String workspace) {
        return service.stats(ontologyId, workspace(workspace));
    }

    private String workspace(String requested) {
        if (requested != null && !requested.trim().isEmpty()) {
            return requested.trim();
        }
        String fallback = properties.getDefaultWorkspace();
        return fallback == null || fallback.trim().isEmpty() ? "default" : fallback;
    }

    /** 来源视图：不暴露原始对象，只给 id / 描述。 */
    public record SourceView(String id, String description) {
        static SourceView of(OntologySource source) {
            return new SourceView(source.id(), source.description());
        }
    }
}
