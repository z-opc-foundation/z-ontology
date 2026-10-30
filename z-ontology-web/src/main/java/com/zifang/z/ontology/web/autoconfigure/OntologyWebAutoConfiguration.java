package com.zifang.z.ontology.web.autoconfigure;

import com.zifang.z.ontology.api.service.OntologyService;
import com.zifang.z.ontology.starter.autoconfigure.OntologyAutoConfiguration;
import com.zifang.z.ontology.web.controller.OntologyRuntimeController;
import com.zifang.z.ontology.web.controller.OntologySchemaController;
import com.zifang.z.ontology.web.support.OntologyExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 本体 REST 层装配：只在 Servlet 容器且已有 {@link OntologyService} 时生效。
 */
@AutoConfiguration(after = OntologyAutoConfiguration.class)
@ConditionalOnClass({DispatcherServlet.class, OntologyService.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(OntologyService.class)
@Import({OntologySchemaController.class, OntologyRuntimeController.class, OntologyExceptionHandler.class})
public class OntologyWebAutoConfiguration {
}
