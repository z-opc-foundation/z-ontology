package com.zifang.z.ontology.kb;

import com.zifang.z.kb.api.Entity;
import com.zifang.z.kb.api.EntityType;
import com.zifang.z.kb.api.Relation;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.OntologySnapshot;
import com.zifang.z.ontology.api.model.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * z-kb 的 Entity / Relation 到本体个体 / 断言的映射。
 *
 * <p>key 统一用 canonicalName：它是 z-kb 的规范化主键（也允许为 null 的 id 跳过的实体）。
 * 关系谓词保持原样（RelationType 名称或自定义字符串），未知谓词交给校验器报
 * {@code UNKNOWN_PROPERTY}，不做静默改名。</p>
 */
public class KbOntologyMapper {

    public OntologySnapshot toSnapshot(String workspace, List<Entity> entities, List<Relation> relations) {
        List<Individual> individuals = new ArrayList<>();
        if (entities != null) {
            for (Entity entity : entities) {
                Individual individual = toIndividual(entity);
                if (individual != null) {
                    individuals.add(individual);
                }
            }
        }
        List<Statement> statements = new ArrayList<>();
        if (relations != null) {
            for (Relation relation : relations) {
                Statement statement = toStatement(relation);
                if (statement != null) {
                    statements.add(statement);
                }
            }
        }
        return new OntologySnapshot(workspace, individuals, statements);
    }

    public Individual toIndividual(Entity entity) {
        if (entity == null || isBlank(entity.getCanonicalName())) {
            return null;
        }
        String key = entity.getCanonicalName();
        Individual individual = new Individual(key, key);
        individual.setLabel(isBlank(entity.getDisplayName()) ? key : entity.getDisplayName());
        EntityType type = entity.getType() == null ? EntityType.CONCEPT : entity.getType();
        individual.addType(type.name());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kbId", entity.getId());
        props.put("entityType", type.name());
        props.put("workspace", entity.getWorkspace());
        props.put("description", entity.getDescription());
        props.put("aliases", new ArrayList<>(nullSafe(entity.getAliases())));
        props.put("frequency", entity.getFrequency());
        props.put("importance", entity.getImportance());
        props.put("sourceChunkIds", new ArrayList<>(nullSafe(entity.getSourceChunkIds())));
        props.put("sourceDocumentIds", new ArrayList<>(nullSafe(entity.getSourceDocumentIds())));
        if (entity.getProperties() != null) {
            props.putAll(entity.getProperties());
        }
        individual.setProperties(props);

        List<String> sources = new ArrayList<>(nullSafe(entity.getSourceDocumentIds()));
        for (String chunkId : nullSafe(entity.getSourceChunkIds())) {
            if (!sources.contains(chunkId)) {
                sources.add(chunkId);
            }
        }
        individual.setSources(sources);
        return individual;
    }

    public Statement toStatement(Relation relation) {
        if (relation == null || isBlank(relation.getSourceEntityName())
                || isBlank(relation.getTargetEntityName())) {
            return null;
        }
        Statement statement = Statement.of(relation.getSourceEntityName(),
                relation.getType(), relation.getTargetEntityName());
        statement.setWeight(relation.getWeight());
        statement.setSources(new ArrayList<>(nullSafe(relation.getEvidenceChunkIds())));
        return statement;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
