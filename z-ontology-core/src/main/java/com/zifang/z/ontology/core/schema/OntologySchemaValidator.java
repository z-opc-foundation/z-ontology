package com.zifang.z.ontology.core.schema;

import com.zifang.z.ontology.api.model.DataTypes;
import com.zifang.z.ontology.api.model.IssueSeverity;
import com.zifang.z.ontology.api.model.OntologyClass;
import com.zifang.z.ontology.api.model.OntologyProperty;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.PropertyKind;
import com.zifang.z.ontology.api.model.ValidationIssue;
import com.zifang.z.ontology.api.model.ValidationReport;
import com.zifang.z.ontology.core.support.ClassHierarchy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本体 Schema 自身的合法性校验：引用存在、无环、kind 一致、domain/range 可解析。
 */
public class OntologySchemaValidator {

    private static final Set<String> RESERVED_PROPERTY_NAMES = new java.util.HashSet<>(
            java.util.Arrays.asList("SUB_CLASS_OF", "INSTANCE_OF"));

    public ValidationReport validate(OntologySchema schema) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (schema == null) {
            issues.add(ValidationIssue.error("SCHEMA_REQUIRED", null, "schema is null"));
            return new ValidationReport(issues);
        }
        if (isBlank(schema.getId())) {
            issues.add(ValidationIssue.error("SCHEMA_ID_REQUIRED", null, "schema.id is required"));
        }
        if (isBlank(schema.getName())) {
            issues.add(ValidationIssue.warning("SCHEMA_NAME_MISSING", schema.getId(), "schema.name is blank"));
        }

        Set<String> classNames = new HashSet<>();
        for (OntologyClass c : schema.getClasses()) {
            if (isBlank(c.getName())) {
                issues.add(ValidationIssue.error("CLASS_NAME_REQUIRED", schema.getId(), "class name is blank"));
                continue;
            }
            if (!classNames.add(c.getName())) {
                issues.add(ValidationIssue.error("DUPLICATE_CLASS", c.getName(), "duplicate class name"));
            }
            if (hasWhitespace(c.getName())) {
                issues.add(ValidationIssue.error("INVALID_CLASS_NAME", c.getName(), "class name must not contain whitespace"));
            }
        }
        Set<String> propertyNames = new HashSet<>();
        for (OntologyProperty p : schema.getProperties()) {
            if (isBlank(p.getName())) {
                issues.add(ValidationIssue.error("PROPERTY_NAME_REQUIRED", schema.getId(), "property name is blank"));
                continue;
            }
            if (!propertyNames.add(p.getName())) {
                issues.add(ValidationIssue.error("DUPLICATE_PROPERTY", p.getName(), "duplicate property name"));
            }
            if (hasWhitespace(p.getName())) {
                issues.add(ValidationIssue.error("INVALID_PROPERTY_NAME", p.getName(), "property name must not contain whitespace"));
            }
            if (RESERVED_PROPERTY_NAMES.contains(p.getName())) {
                issues.add(ValidationIssue.error("RESERVED_PROPERTY_NAME", p.getName(),
                        "property name is reserved by the graph mapping: " + p.getName()));
            }
        }

        for (OntologyClass c : schema.getClasses()) {
            if (isBlank(c.getName())) continue;
            for (String parent : c.getSuperClasses()) {
                if (parent == null || parent.equals(c.getName())) {
                    issues.add(ValidationIssue.error("INVALID_SUPER_CLASS", c.getName(), "super class must not be self"));
                } else if (!classNames.contains(parent)) {
                    issues.add(ValidationIssue.error("UNKNOWN_SUPER_CLASS", c.getName(), "unknown super class: " + parent));
                }
            }
            for (String other : c.getDisjointWith()) {
                if (other == null || other.equals(c.getName())) {
                    issues.add(ValidationIssue.error("INVALID_DISJOINT", c.getName(), "disjointWith must not be self"));
                } else if (!classNames.contains(other)) {
                    issues.add(ValidationIssue.error("UNKNOWN_DISJOINT_CLASS", c.getName(), "unknown disjoint class: " + other));
                }
            }
        }

        for (OntologyProperty p : schema.getProperties()) {
            if (isBlank(p.getName())) continue;
            if (p.getDomain() != null && !classNames.contains(p.getDomain())) {
                issues.add(ValidationIssue.error("UNKNOWN_DOMAIN", p.getName(), "unknown domain class: " + p.getDomain()));
            }
            PropertyKind kind = p.getKind() == null ? PropertyKind.OBJECT : p.getKind();
            if (p.getRange() != null) {
                if (kind == PropertyKind.OBJECT) {
                    if (!classNames.contains(p.getRange())) {
                        issues.add(ValidationIssue.error("UNKNOWN_RANGE", p.getName(), "unknown range class: " + p.getRange()));
                    }
                } else if (!DataTypes.SUPPORTED.contains(p.getRange())) {
                    issues.add(ValidationIssue.error("UNKNOWN_DATATYPE", p.getName(), "unknown datatype range: " + p.getRange()));
                }
            }
            for (String parent : p.getSuperProperties()) {
                OntologyProperty parentProp = schema.findProperty(parent);
                if (parentProp == null) {
                    issues.add(ValidationIssue.error("UNKNOWN_SUPER_PROPERTY", p.getName(), "unknown super property: " + parent));
                } else if (parentProp.getKind() != kind) {
                    issues.add(ValidationIssue.error("PROPERTY_KIND_MISMATCH", p.getName(),
                            "super property " + parent + " has kind " + parentProp.getKind()));
                }
            }
            if (p.getInverseOf() != null) {
                OntologyProperty inverse = schema.findProperty(p.getInverseOf());
                if (inverse == null) {
                    issues.add(ValidationIssue.error("UNKNOWN_INVERSE", p.getName(), "unknown inverse property: " + p.getInverseOf()));
                } else if (inverse.getKind() != PropertyKind.OBJECT) {
                    issues.add(ValidationIssue.error("INVERSE_NOT_OBJECT", p.getName(), "inverse property must be OBJECT"));
                } else if (inverse.getName().equals(p.getName())) {
                    issues.add(ValidationIssue.error("INVERSE_SELF", p.getName(), "property must not be its own inverse"));
                }
            }
            if (kind == PropertyKind.DATA) {
                if (p.isTransitive()) {
                    issues.add(ValidationIssue.error("TRANSITIVE_DATA_PROPERTY", p.getName(), "data property cannot be transitive"));
                }
                if (p.isSymmetric()) {
                    issues.add(ValidationIssue.error("SYMMETRIC_DATA_PROPERTY", p.getName(), "data property cannot be symmetric"));
                }
            }
        }

        ClassHierarchy hierarchy = new ClassHierarchy(schema);
        List<String> classCycle = hierarchy.findClassCycle();
        if (!classCycle.isEmpty()) {
            issues.add(ValidationIssue.error("CLASS_CYCLE", classCycle.get(0), "subClassOf cycle: " + String.join(" -> ", classCycle)));
        }
        List<String> propertyCycle = hierarchy.findPropertyCycle();
        if (!propertyCycle.isEmpty()) {
            issues.add(ValidationIssue.error("PROPERTY_CYCLE", propertyCycle.get(0),
                    "subPropertyOf cycle: " + String.join(" -> ", propertyCycle)));
        }
        return new ValidationReport(issues);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean hasWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    public static boolean hasErrors(ValidationReport report) {
        for (ValidationIssue issue : report.getIssues()) {
            if (issue.getSeverity() == IssueSeverity.ERROR) return true;
        }
        return false;
    }
}
