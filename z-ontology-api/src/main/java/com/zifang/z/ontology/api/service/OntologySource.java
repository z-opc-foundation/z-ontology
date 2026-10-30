package com.zifang.z.ontology.api.service;

import com.zifang.z.ontology.api.model.OntologySnapshot;

/**
 * 知识来源：把某个 workspace 的知识加载成个体 + 断言。
 *
 * <p>z-kb 是内置来源（id=kb）；宿主也可以实现自己的来源。</p>
 */
public interface OntologySource {

    String id();

    String description();

    OntologySnapshot load(String workspace);
}
