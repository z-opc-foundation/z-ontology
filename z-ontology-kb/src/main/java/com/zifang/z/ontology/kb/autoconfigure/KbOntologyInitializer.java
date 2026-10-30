package com.zifang.z.ontology.kb.autoconfigure;

import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.service.OntologyService;
import com.zifang.z.ontology.kb.KbSchemaFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 宿主里同时有 z-kb 和 z-ontology 时，把默认 kb 本体 Schema 登记进去。
 *
 * <p>已经存在同版本 Schema 时不动；版本号变了才覆盖。</p>
 */
public class KbOntologyInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(KbOntologyInitializer.class);

    private final ObjectProvider<OntologyService> serviceProvider;
    private final KbSchemaFactory schemaFactory;

    public KbOntologyInitializer(ObjectProvider<OntologyService> serviceProvider, KbSchemaFactory schemaFactory) {
        this.serviceProvider = serviceProvider;
        this.schemaFactory = schemaFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        OntologyService service = serviceProvider.getIfAvailable();
        if (service == null) {
            log.debug("OntologyService 不在容器里，跳过 kb 默认 Schema 登记");
            return;
        }
        OntologySchema schema = schemaFactory.create();
        boolean upToDate = service.findSchema(schema.getId())
                .filter(existing -> schema.getVersion().equals(existing.getVersion()))
                .isPresent();
        if (upToDate) {
            return;
        }
        service.registerSchema(schema);
        log.info("已登记 kb 本体 Schema: id={}, version={}, classes={}, properties={}",
                schema.getId(), schema.getVersion(), schema.getClasses().size(), schema.getProperties().size());
    }
}
