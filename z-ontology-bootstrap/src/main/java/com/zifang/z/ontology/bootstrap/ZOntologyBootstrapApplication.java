package com.zifang.z.ontology.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * z-ontology 独立启动器：默认 8890 端口，开箱可注册本体、物化图、查询实例/邻居/子图。
 */
@SpringBootApplication
public class ZOntologyBootstrapApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZOntologyBootstrapApplication.class, args);
    }
}
