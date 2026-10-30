package com.zifang.z.ontology.core.validation;

import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.IssueSeverity;
import com.zifang.z.ontology.api.model.OntologyProperty;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.PropertyKind;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.api.model.ValidationIssue;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.core.support.ClassHierarchy;
import com.zifang.z.ontology.core.support.LiteralValues;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一致性校验：未知类/属性、domain/range、互斥类、函数性冲突、字面量类型。
 *
 * <p>domain/range 只检查显式断言；推断出来的断言由推理规则保证。</p>
 */
public class OntologyValidator {

    public ValidationReport validate(OntologySchema schema, InferenceResult result) {
        ClassHierarchy hierarchy = new ClassHierarchy(schema);
        Map<String, Individual> byKey = new LinkedHashMap<>();
        for (Individual ind : result.getIndividuals()) {
            if (ind.getKey() != null) byKey.put(ind.getKey(), ind);
        }

        Map<String, ValidationIssue> issues = new LinkedHashMap<>();

        for (Individual ind : result.getIndividuals()) {
            Set<String> allTypes = ind.allTypes();
            if (allTypes.isEmpty()) {
                put(issues, new ValidationIssue(IssueSeverity.WARNING, "UNTYPED_INDIVIDUAL",
                        ind.getKey(), null, "individual has no type"));
            }
            for (String type : allTypes) {
                if (schema.findClass(type) == null) {
                    put(issues, new ValidationIssue(IssueSeverity.ERROR, "UNKNOWN_CLASS",
                            ind.getKey(), null, "unknown class: " + type));
                }
            }
            List<String> types = new ArrayList<>(allTypes);
            for (int i = 0; i < types.size(); i++) {
                for (int j = i + 1; j < types.size(); j++) {
                    if (hierarchy.isDisjoint(types.get(i), types.get(j))) {
                        put(issues, new ValidationIssue(IssueSeverity.ERROR, "DISJOINT_VIOLATION",
                                ind.getKey(), null,
                                "individual is both " + types.get(i) + " and " + types.get(j)
                                        + " but the classes are disjoint"));
                    }
                }
            }
        }

        Map<String, Set<String>> functionalValues = new LinkedHashMap<>();
        for (Statement s : result.getStatements()) {
            if (s.isInferred()) continue;
            if (!byKey.containsKey(s.getSubject())) {
                put(issues, new ValidationIssue(IssueSeverity.WARNING, "UNKNOWN_SUBJECT",
                        s.getSubject(), s.getPredicate(), "statement subject is not a declared individual"));
            }
            OntologyProperty p = schema.findProperty(s.getPredicate());
            if (p == null) {
                put(issues, new ValidationIssue(IssueSeverity.ERROR, "UNKNOWN_PROPERTY",
                        s.getSubject(), s.getPredicate(), "unknown property: " + s.getPredicate()));
                continue;
            }
            if (p.getKind() == PropertyKind.OBJECT && s.isLiteral()) {
                put(issues, new ValidationIssue(IssueSeverity.ERROR, "LITERAL_MISMATCH",
                        s.getSubject(), s.getPredicate(), "object property cannot take a literal value"));
            }
            if (p.getKind() == PropertyKind.DATA && !s.isLiteral()) {
                put(issues, new ValidationIssue(IssueSeverity.ERROR, "LITERAL_MISMATCH",
                        s.getSubject(), s.getPredicate(), "data property requires a literal value"));
            }
            if (p.getKind() == PropertyKind.DATA && s.isLiteral()) {
                String error = LiteralValues.typeError(s.getObject(), p.getRange());
                if (error != null) {
                    put(issues, new ValidationIssue(IssueSeverity.ERROR, "INVALID_LITERAL",
                            s.getSubject(), s.getPredicate(), error));
                }
            }

            if (p.getDomain() != null) {
                Individual subject = byKey.get(s.getSubject());
                // domain/range 在推理阶段会补齐类型，这里只跟显式声明的类型比对，
                // 否则被补上的类型永远"满足"，违反只能靠 disjoint 间接发现。
                if (subject != null && !subject.getTypes().isEmpty()
                        && !matchesAny(subject.getTypes(), p.getDomain(), hierarchy)) {
                    put(issues, new ValidationIssue(IssueSeverity.ERROR, "DOMAIN_VIOLATION",
                            s.getSubject(), s.getPredicate(),
                            "subject is not a " + p.getDomain() + " (types=" + subject.getTypes() + ")"));
                }
            }
            if (p.getKind() == PropertyKind.OBJECT && !s.isLiteral()) {
                Individual object = byKey.get(s.getObject());
                if (object == null) {
                    put(issues, new ValidationIssue(IssueSeverity.WARNING, "UNKNOWN_OBJECT",
                            s.getSubject(), s.getPredicate(),
                            "statement object is not a declared individual: " + s.getObject()));
                } else if (p.getRange() != null && !object.getTypes().isEmpty()
                        && !matchesAny(object.getTypes(), p.getRange(), hierarchy)) {
                    put(issues, new ValidationIssue(IssueSeverity.ERROR, "RANGE_VIOLATION",
                            s.getSubject(), s.getPredicate(),
                            "object is not a " + p.getRange() + " (types=" + object.getTypes() + ")"));
                }
            }

            if (p.isFunctional()) {
                String key = s.getSubject() + "\u0000" + s.getPredicate();
                functionalValues.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(s.getObject());
            }
        }

        for (Map.Entry<String, Set<String>> entry : functionalValues.entrySet()) {
            if (entry.getValue().size() > 1) {
                String[] parts = entry.getKey().split("\u0000", 2);
                put(issues, new ValidationIssue(IssueSeverity.ERROR, "FUNCTIONAL_CONFLICT",
                        parts[0], parts.length > 1 ? parts[1] : null,
                        "functional property has multiple values: " + entry.getValue()));
            }
        }

        List<ValidationIssue> sorted = new ArrayList<>(issues.values());
        sorted.sort(Comparator
                .comparing((ValidationIssue i) -> i.getSeverity().ordinal())
                .thenComparing(i -> i.getCode() == null ? "" : i.getCode())
                .thenComparing(i -> i.getSubject() == null ? "" : i.getSubject()));
        return new ValidationReport(sorted);
    }

    private static boolean matchesAny(Set<String> types, String expected, ClassHierarchy hierarchy) {
        for (String type : types) {
            if (hierarchy.isSubclassOf(type, expected)) return true;
        }
        return false;
    }

    private static void put(Map<String, ValidationIssue> issues, ValidationIssue issue) {
        String key = issue.getCode() + "\u0000" + issue.getSubject() + "\u0000"
                + issue.getPredicate() + "\u0000" + issue.getMessage();
        issues.putIfAbsent(key, issue);
    }
}
