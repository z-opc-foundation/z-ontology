package com.zifang.z.ontology.kb;

import com.zifang.z.kb.api.Entity;
import com.zifang.z.kb.api.KnowledgeGraphStore;
import com.zifang.z.kb.api.Relation;
import com.zifang.z.ontology.api.model.OntologySnapshot;
import com.zifang.z.ontology.api.service.OntologySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 z-kb 某个 workspace 的知识图谱整体读成本体快照。
 *
 * <p>实体取 {@link KnowledgeGraphStore#listAllEntities}，关系取每个实体的出边并按 id 去重
 * （入边必然是对端实体的出边，再读一遍只会重复）。单次加载上限可配，默认 5000。</p>
 */
public class KbOntologySource implements OntologySource {

    public static final String SOURCE_ID = "kb";
    public static final int DEFAULT_LIMIT = 5000;

    private static final Logger log = LoggerFactory.getLogger(KbOntologySource.class);

    private final KnowledgeGraphStore store;
    private final KbOntologyMapper mapper;
    private final int limit;

    public KbOntologySource(KnowledgeGraphStore store) {
        this(store, new KbOntologyMapper(), DEFAULT_LIMIT);
    }

    public KbOntologySource(KnowledgeGraphStore store, KbOntologyMapper mapper, int limit) {
        if (store == null) {
            throw new IllegalArgumentException("KnowledgeGraphStore is required");
        }
        this.store = store;
        this.mapper = mapper == null ? new KbOntologyMapper() : mapper;
        this.limit = limit <= 0 ? DEFAULT_LIMIT : limit;
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String description() {
        return "z-kb knowledge graph (workspace-scoped entities and relations)";
    }

    @Override
    public OntologySnapshot load(String workspace) {
        List<Entity> entities = store.listAllEntities(workspace, limit);
        List<Relation> relations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (entities != null) {
            for (Entity entity : entities) {
                if (entity == null || entity.getCanonicalName() == null) {
                    continue;
                }
                List<Relation> outgoing = store.getOutRelations(workspace, entity.getCanonicalName());
                if (outgoing == null) {
                    continue;
                }
                for (Relation relation : outgoing) {
                    if (relation == null) {
                        continue;
                    }
                    if (relation.getId() == null || seen.add(relation.getId())) {
                        relations.add(relation);
                    }
                }
            }
        }
        if (entities != null && entities.size() >= limit) {
            log.warn("z-kb ontology source hit the entity limit {} (workspace={}); "
                    + "raise zont.kb.limit if the graph is larger", limit, workspace);
        }
        return mapper.toSnapshot(workspace, entities, relations);
    }
}
