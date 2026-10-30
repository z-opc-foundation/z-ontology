package com.zifang.z.ontology.core.materialize;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.MaterializationReport;
import com.zifang.z.ontology.api.model.OntologyClass;
import com.zifang.z.ontology.api.model.OntologyProperty;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.PropertyKind;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.api.service.OntologyGraphPort;
import com.zifang.z.ontology.core.support.LiteralValues;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把推理后的本体写进图：类节点、个体节点、子类边、实例化边、对象属性边、数据属性。
 *
 * <p>图模型约定：</p>
 * <ul>
 *   <li>类节点 key = class:&lt;类名&gt;，labels = OntologyClass + 类名</li>
 *   <li>个体节点 key = individual:&lt;个体 key&gt;，labels = OntologyIndividual + 全部类型</li>
 *   <li>子类边 SUB_CLASS_OF：类 → 父类</li>
 *   <li>实例化边 INSTANCE_OF：个体 → 类（inferred 属性区分显式/推断）</li>
 *   <li>对象属性边 type = 谓词名；数据属性落到个体节点的 data:&lt;谓词&gt; 属性</li>
 * </ul>
 */
public class OntologyMaterializer {

    public MaterializationReport materialize(OntologySchema schema, String workspace, String sourceId,
                                             InferenceResult inference, ValidationReport validation,
                                             OntologyGraphPort port) {
        long start = System.currentTimeMillis();
        String ontologyId = schema.getId();
        port.reset(ontologyId, workspace);

        int classNodes = 0;
        int edges = 0;

        for (OntologyClass c : schema.getClasses()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("ontologyId", ontologyId);
            props.put("workspace", workspace);
            props.put("name", c.getName());
            props.put("label", c.getLabel());
            props.put("description", c.getDescription());
            props.put("superClasses", new ArrayList<>(c.getSuperClasses()));
            props.put("disjointWith", new ArrayList<>(c.getDisjointWith()));
            List<String> labels = labelsOf("OntologyClass", c.getName());
            port.upsertNode(ontologyId, workspace, classKey(c.getName()), labels, props);
            classNodes++;
        }
        for (OntologyClass c : schema.getClasses()) {
            for (String parent : c.getSuperClasses()) {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("ontologyId", ontologyId);
                props.put("workspace", workspace);
                props.put("inferred", false);
                port.upsertEdge(ontologyId, workspace,
                        "subclass:" + c.getName() + ":" + parent, "SUB_CLASS_OF",
                        classKey(c.getName()), classKey(parent), props);
                edges++;
            }
        }

        Set<String> knownIndividuals = new LinkedHashSet<>();
        int individualNodes = 0;
        for (Individual ind : inference.getIndividuals()) {
            knownIndividuals.add(ind.getKey());
            port.upsertNode(ontologyId, workspace, individualKey(ind.getKey()),
                    individualLabels(ind), individualProps(ontologyId, workspace, ind));
            individualNodes++;
        }

        for (Individual ind : inference.getIndividuals()) {
            for (String type : ind.getTypes()) {
                edges += instanceEdge(ontologyId, workspace, port, schema, ind.getKey(), type, false);
            }
            for (String type : ind.getInferredTypes()) {
                edges += instanceEdge(ontologyId, workspace, port, schema, ind.getKey(), type, true);
            }
        }

        Map<String, Map<String, Object>> dataProps = new LinkedHashMap<>();
        for (Statement s : inference.getStatements()) {
            if (s.isLiteral()) {
                OntologyProperty p = schema.findProperty(s.getPredicate());
                String range = p == null ? null : p.getRange();
                Object value = LiteralValues.convert(s.getObject(), range);
                dataProps.computeIfAbsent(s.getSubject(), k -> new LinkedHashMap<>())
                        .compute("data:" + s.getPredicate(), (k, old) -> mergeValue(old, value));
                continue;
            }
            ensureNode(ontologyId, workspace, port, s.getSubject(), knownIndividuals);
            ensureNode(ontologyId, workspace, port, s.getObject(), knownIndividuals);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("ontologyId", ontologyId);
            props.put("workspace", workspace);
            props.put("predicate", s.getPredicate());
            props.put("weight", s.getWeight());
            props.put("inferred", s.isInferred());
            props.put("sources", new ArrayList<>(s.getSources()));
            port.upsertEdge(ontologyId, workspace,
                    "stmt:" + s.getSubject() + ":" + s.getPredicate() + ":" + s.getObject(),
                    s.getPredicate(), individualKey(s.getSubject()), individualKey(s.getObject()), props);
            edges++;
        }

        for (Map.Entry<String, Map<String, Object>> entry : dataProps.entrySet()) {
            Individual ind = findIndividual(inference, entry.getKey());
            List<String> labels = ind == null ? labelsOf("OntologyIndividual") : individualLabels(ind);
            port.upsertNode(ontologyId, workspace, individualKey(entry.getKey()), labels, entry.getValue());
        }

        MaterializationReport report = new MaterializationReport();
        report.setOntologyId(ontologyId);
        report.setWorkspace(workspace);
        report.setSourceId(sourceId);
        report.setClassNodes(classNodes);
        report.setIndividualNodes(individualNodes);
        report.setEdges(edges);
        report.setExplicitStatements(inference.getExplicitStatements());
        report.setInferredStatements(inference.getInferredStatements());
        report.setValidation(validation);
        report.setMaterializedAt(Instant.now());
        report.setDurationMs(System.currentTimeMillis() - start);
        return report;
    }

    private static int instanceEdge(String ontologyId, String workspace, OntologyGraphPort port,
                                    OntologySchema schema, String key, String className,
                                    boolean inferred) {
        if (schema.findClass(className) == null) return 0;
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("ontologyId", ontologyId);
        props.put("workspace", workspace);
        props.put("inferred", inferred);
        port.upsertEdge(ontologyId, workspace,
                "instance:" + key + ":" + className, "INSTANCE_OF",
                individualKey(key), classKey(className), props);
        return 1;
    }

    private static void ensureNode(String ontologyId, String workspace, OntologyGraphPort port,
                                   String key, Set<String> knownIndividuals) {
        if (key == null || knownIndividuals.contains(key)) return;
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("ontologyId", ontologyId);
        props.put("workspace", workspace);
        props.put("key", key);
        props.put("name", key);
        props.put("placeholder", true);
        port.upsertNode(ontologyId, workspace, individualKey(key),
                labelsOf("OntologyIndividual"), props);
        knownIndividuals.add(key);
    }

    private static Individual findIndividual(InferenceResult inference, String key) {
        for (Individual ind : inference.getIndividuals()) {
            if (key != null && key.equals(ind.getKey())) return ind;
        }
        return null;
    }

    private static Map<String, Object> individualProps(String ontologyId, String workspace, Individual ind) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("ontologyId", ontologyId);
        props.put("workspace", workspace);
        props.put("key", ind.getKey());
        props.put("name", ind.getName());
        props.put("label", ind.getLabel());
        props.put("types", new ArrayList<>(ind.getTypes()));
        props.put("inferredTypes", new ArrayList<>(ind.getInferredTypes()));
        props.put("allTypes", new ArrayList<>(ind.allTypes()));
        props.put("sources", new ArrayList<>(ind.getSources()));
        props.put("attributesJson", JsonUtil.toJsonQuietly(ind.getProperties()));
        return props;
    }

    private static List<String> individualLabels(Individual ind) {
        List<String> labels = labelsOf("OntologyIndividual");
        labels.addAll(ind.allTypes());
        return labels;
    }

    private static List<String> labelsOf(String... labels) {
        return new ArrayList<>(Arrays.asList(labels));
    }

    private static Object mergeValue(Object old, Object value) {
        if (old == null) return value;
        if (old instanceof List) {
            List<Object> list = new ArrayList<>((List<?>) old);
            list.add(value);
            return list;
        }
        List<Object> list = new ArrayList<>();
        list.add(old);
        list.add(value);
        return list;
    }

    private static String classKey(String className) {
        return "class:" + className;
    }

    private static String individualKey(String key) {
        return "individual:" + key;
    }
}
