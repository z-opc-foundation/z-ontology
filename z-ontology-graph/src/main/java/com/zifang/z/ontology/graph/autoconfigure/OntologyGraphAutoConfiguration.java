package com.zifang.z.ontology.graph.autoconfigure;

import com.zifang.z.graph.api.GraphStore;
import com.zifang.z.graph.core.InMemoryGraphStore;
import com.zifang.z.ontology.api.service.OntologyGraphPort;
import com.zifang.z.ontology.graph.adapter.ZGraphOntologyPort;
import com.zifang.z.ontology.graph.store.JsonlGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * 把 z-graph 装配成本体层的物化图端口。
 *
 * <p>z-graph starter 只暴露 {@code GraphVersionStore} + 控制面，不提供 {@link GraphStore}，
 * 所以这里在宿主没给的时候兜底：配置了 {@code zont.graph.path} 就用文件持久存储（JSONL 日志，
 * 重启不丢），否则退回进程内实现。宿主自带 GraphStore 时直接用宿主的。
 * 想换图后端只要自己声明一个 {@link OntologyGraphPort} Bean。</p>
 */
@AutoConfiguration
@ConditionalOnClass({GraphStore.class, InMemoryGraphStore.class})
public class OntologyGraphAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OntologyGraphAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(GraphStore.class)
    public GraphStore ontologyGraphStore(@Value("${zont.graph.path:}") String path) {
        if (path == null || path.isBlank()) {
            log.info("z-ontology 物化图使用进程内存储（未配置 zont.graph.path，重启丢失）");
            return new InMemoryGraphStore();
        }
        Path file = Path.of(expandHome(path.trim())).resolve("graph.jsonl");
        log.info("z-ontology 物化图持久化文件: {}", file);
        return new JsonlGraphStore(file);
    }

    @Bean
    @ConditionalOnMissingBean(OntologyGraphPort.class)
    public OntologyGraphPort ontologyGraphPort(GraphStore graphStore) {
        return new ZGraphOntologyPort(graphStore);
    }

    private static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
