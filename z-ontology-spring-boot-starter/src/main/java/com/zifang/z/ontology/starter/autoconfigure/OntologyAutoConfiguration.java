package com.zifang.z.ontology.starter.autoconfigure;

import com.zifang.z.ontology.api.service.OntologyGraphPort;
import com.zifang.z.ontology.api.service.OntologyService;
import com.zifang.z.ontology.api.service.OntologySource;
import com.zifang.z.ontology.api.service.OntologyStore;
import com.zifang.z.ontology.core.graph.SimpleOntologyGraphPort;
import com.zifang.z.ontology.core.materialize.OntologyMaterializer;
import com.zifang.z.ontology.core.reasoning.RdfsReasoner;
import com.zifang.z.ontology.core.schema.JsonFileOntologyStore;
import com.zifang.z.ontology.core.service.DefaultOntologyService;
import com.zifang.z.ontology.core.validation.OntologyValidator;
import com.zifang.z.ontology.starter.config.OntologyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;

/**
 * z-ontology 核心装配：Schema 存储、推理器、校验器、物化器、默认服务。
 *
 * <p>图端口交给宿主：装了 {@code z-ontology-graph} 就自动用 z-graph，没装就退回进程内实现。
 * 这样 starter 本身不硬依赖 z-graph，测试与轻量宿主都能起。</p>
 */
@AutoConfiguration
@ConditionalOnClass({OntologyService.class, RdfsReasoner.class})
@ConditionalOnProperty(prefix = "zont", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OntologyProperties.class)
public class OntologyAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OntologyAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public OntologyStore ontologyStore(OntologyProperties properties) {
        Path dir = properties.resolveStoragePath();
        log.info("z-ontology Schema 存储目录: {}", dir);
        return new JsonFileOntologyStore(dir);
    }

    @Bean
    @ConditionalOnMissingBean
    public RdfsReasoner rdfsReasoner() {
        return new RdfsReasoner();
    }

    @Bean
    @ConditionalOnMissingBean
    public OntologyValidator ontologyValidator() {
        return new OntologyValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public OntologyMaterializer ontologyMaterializer() {
        return new OntologyMaterializer();
    }

    @Bean
    @ConditionalOnMissingBean
    public OntologyService ontologyService(OntologyStore store, RdfsReasoner reasoner,
                                           OntologyValidator validator, OntologyMaterializer materializer,
                                           ObjectProvider<OntologyGraphPort> graphPort,
                                           ObjectProvider<OntologySource> sources) {
        OntologyGraphPort port = graphPort.getIfAvailable(() -> {
            log.warn("没有 OntologyGraphPort Bean，物化图回落为进程内实现（重启丢失）");
            return new SimpleOntologyGraphPort();
        });
        List<OntologySource> sourceList = sources.orderedStream().toList();
        if (!sourceList.isEmpty()) {
            log.info("z-ontology 已登记知识来源: {}",
                    sourceList.stream().map(OntologySource::id).toList());
        }
        return new DefaultOntologyService(store, reasoner, validator, materializer, port, sourceList);
    }
}
