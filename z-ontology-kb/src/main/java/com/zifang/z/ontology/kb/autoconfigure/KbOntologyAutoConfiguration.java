package com.zifang.z.ontology.kb.autoconfigure;

import com.zifang.z.kb.api.KnowledgeGraphStore;
import com.zifang.z.ontology.api.service.OntologyService;
import com.zifang.z.ontology.kb.KbOntologyMapper;
import com.zifang.z.ontology.kb.KbOntologySource;
import com.zifang.z.ontology.kb.KbSchemaFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

/**
 * z-kb 适配自动装配：默认 Schema 工厂、映射器、kb 本体来源、以及 Schema 登记。
 *
 * <p>只在宿主真有 {@link KnowledgeGraphStore} 时才产出 kb 来源；z-ontology 单独使用时
 * 这个 jar 在不在都不影响启动。</p>
 */
@AutoConfiguration(afterName = {
        "com.zifang.z.kb.graph.autoconfigure.KnowledgeGraphStoreAutoConfiguration",
        "com.zifang.z.kb.starter.config.KBDevProfileAutoConfiguration"
})
@ConditionalOnClass({KnowledgeGraphStore.class, OntologyService.class})
@ConditionalOnProperty(prefix = "zont", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KbOntologyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KbSchemaFactory kbSchemaFactory() {
        return new KbSchemaFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public KbOntologyMapper kbOntologyMapper() {
        return new KbOntologyMapper();
    }

    @Bean
    @ConditionalOnBean(KnowledgeGraphStore.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zont.kb", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KbOntologySource kbOntologySource(KnowledgeGraphStore graphStore, KbOntologyMapper mapper,
                                             @Value("${zont.kb.limit:5000}") int limit) {
        return new KbOntologySource(graphStore, mapper, limit);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zont.kb", name = "auto-schema", havingValue = "true", matchIfMissing = true)
    public KbOntologyInitializer kbOntologyInitializer(ObjectProvider<OntologyService> serviceProvider,
                                                       KbSchemaFactory schemaFactory) {
        return new KbOntologyInitializer(serviceProvider, schemaFactory);
    }
}
