package com.zifang.z.ontology.graph.autoconfigure;

import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.core.InMemoryGraphStore;
import com.zifang.z.ontology.api.service.OntologyGraphPort;
import com.zifang.z.ontology.graph.adapter.ZGraphOntologyPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 把 z-graph 装配成本体层的物化图端口。
 *
 * <p>z-graph starter 只暴露 {@code GraphVersionStore} + 控制面，不提供 {@link GraphStore}，
 * 所以这里在宿主没给的时候兜底一个 {@link InMemoryGraphStore}；宿主自带 GraphStore 时直接用宿主的。
 * 想换图后端只要自己声明一个 {@link OntologyGraphPort} Bean。</p>
 */
@AutoConfiguration
@ConditionalOnClass({GraphStore.class, InMemoryGraphStore.class})
public class OntologyGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GraphStore.class)
    public GraphStore ontologyGraphStore() {
        return new InMemoryGraphStore();
    }

    @Bean
    @ConditionalOnMissingBean(OntologyGraphPort.class)
    public OntologyGraphPort ontologyGraphPort(GraphStore graphStore) {
        return new ZGraphOntologyPort(graphStore);
    }
}
