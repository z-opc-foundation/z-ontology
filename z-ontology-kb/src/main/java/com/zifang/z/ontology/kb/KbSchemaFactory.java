package com.zifang.z.ontology.kb;

import com.zifang.z.kb.api.EntityType;
import com.zifang.z.kb.api.RelationType;
import com.zifang.z.ontology.api.model.OntologyClass;
import com.zifang.z.ontology.api.model.OntologyProperty;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.PropertyKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 由 z-kb 的 {@link EntityType} / {@link RelationType} 生成默认本体 Schema。
 *
 * <p>类层次：{@code Entity} 为根类，每个 EntityType 一个子类；每个 RelationType 是一个
 * domain=Entity、range=Entity 的对象属性。这个 Schema 是固定的元本体，和具体工作台的数据无关。</p>
 */
public class KbSchemaFactory {

    public static final String SCHEMA_ID = "kb";
    public static final String SCHEMA_NAME = "z-kb Knowledge Graph Ontology";
    public static final String ROOT_CLASS = "Entity";
    public static final String SCHEMA_VERSION = "1.0.0";

    public OntologySchema create() {
        OntologySchema schema = new OntologySchema();
        schema.setId(SCHEMA_ID);
        schema.setName(SCHEMA_NAME);
        schema.setVersion(SCHEMA_VERSION);
        schema.setDescription("z-kb 实体/关系映射到本体的默认 Schema：Entity 根类 + EntityType 子类 + RelationType 对象属性");

        OntologyClass root = new OntologyClass();
        root.setName(ROOT_CLASS);
        root.setLabel("实体");
        root.setDescription("z-kb 知识图谱实体的公共父类");
        schema.getClasses().add(root);

        for (EntityType type : EntityType.values()) {
            OntologyClass c = new OntologyClass();
            c.setName(type.name());
            c.setLabel(type.name());
            c.setDescription("z-kb 实体类型 " + type.name());
            c.getSuperClasses().add(ROOT_CLASS);
            schema.getClasses().add(c);
        }

        for (RelationType type : RelationType.values()) {
            OntologyProperty p = new OntologyProperty();
            p.setName(type.name());
            p.setKind(PropertyKind.OBJECT);
            p.setLabel(type.name());
            p.setDescription("z-kb 关系类型 " + type.name());
            p.setDomain(ROOT_CLASS);
            p.setRange(ROOT_CLASS);
            schema.getProperties().add(p);
        }
        return schema;
    }

    /** Schema 里的类名清单（供宿主筛选 / 文档展示）。 */
    public List<String> classNames() {
        List<String> names = new ArrayList<>();
        names.add(ROOT_CLASS);
        for (EntityType type : EntityType.values()) {
            names.add(type.name());
        }
        return names;
    }
}
