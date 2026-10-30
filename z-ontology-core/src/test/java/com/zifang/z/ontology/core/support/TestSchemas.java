package com.zifang.z.ontology.core.support;

import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.OntologyClass;
import com.zifang.z.ontology.api.model.OntologyProperty;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.PropertyKind;
import com.zifang.z.ontology.api.model.Statement;

import java.util.List;

/**
 * 测试共用的示例本体：
 * Entity ← Person ← Employee，Entity ← Organization；Person ⊥ Organization。
 */
public final class TestSchemas {

    private TestSchemas() {
    }

    public static OntologySchema people() {
        OntologySchema schema = new OntologySchema("people", "People & Org");
        schema.getClasses().add(cls("Entity", List.of(), List.of()));
        schema.getClasses().add(cls("Person", List.of("Entity"), List.of("Organization")));
        schema.getClasses().add(cls("Employee", List.of("Person"), List.of()));
        schema.getClasses().add(cls("Organization", List.of("Entity"), List.of("Person")));

        OntologyProperty worksFor = prop("worksFor", PropertyKind.OBJECT);
        worksFor.setDomain("Person");
        worksFor.setRange("Organization");
        schema.getProperties().add(worksFor);

        OntologyProperty employedBy = prop("employedBy", PropertyKind.OBJECT);
        employedBy.getSuperProperties().add("worksFor");
        schema.getProperties().add(employedBy);

        OntologyProperty reportsTo = prop("reportsTo", PropertyKind.OBJECT);
        reportsTo.setDomain("Employee");
        reportsTo.setRange("Employee");
        reportsTo.setTransitive(true);
        schema.getProperties().add(reportsTo);

        OntologyProperty knows = prop("knows", PropertyKind.OBJECT);
        knows.setSymmetric(true);
        schema.getProperties().add(knows);

        OntologyProperty spouseOf = prop("spouseOf", PropertyKind.OBJECT);
        spouseOf.setInverseOf("marriedTo");
        schema.getProperties().add(spouseOf);

        OntologyProperty marriedTo = prop("marriedTo", PropertyKind.OBJECT);
        marriedTo.setInverseOf("spouseOf");
        schema.getProperties().add(marriedTo);

        OntologyProperty name = prop("name", PropertyKind.DATA);
        name.setRange("STRING");
        schema.getProperties().add(name);

        OntologyProperty age = prop("age", PropertyKind.DATA);
        age.setRange("INT");
        schema.getProperties().add(age);

        OntologyProperty email = prop("email", PropertyKind.DATA);
        email.setRange("STRING");
        email.setFunctional(true);
        schema.getProperties().add(email);

        return schema;
    }

    public static Individual individual(String key, String... types) {
        Individual individual = new Individual(key, key);
        for (String type : types) {
            individual.addType(type);
        }
        return individual;
    }

    public static Statement object(String subject, String predicate, String object) {
        return Statement.of(subject, predicate, object);
    }

    public static Statement literal(String subject, String predicate, Object value) {
        return Statement.literal(subject, predicate, value);
    }

    private static OntologyClass cls(String name, List<String> supers, List<String> disjoint) {
        OntologyClass c = new OntologyClass();
        c.setName(name);
        c.setSuperClasses(new java.util.ArrayList<>(supers));
        c.setDisjointWith(new java.util.ArrayList<>(disjoint));
        return c;
    }

    private static OntologyProperty prop(String name, PropertyKind kind) {
        OntologyProperty p = new OntologyProperty();
        p.setName(name);
        p.setKind(kind);
        return p;
    }
}
