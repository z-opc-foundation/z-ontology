package com.zifang.z.ontology.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * z-ontology 顶层配置（前缀 zont.*）。
 */
@ConfigurationProperties(prefix = "zont")
public class OntologyProperties {

    /** 是否启用 z-ontology 自动装配 */
    private boolean enabled = true;

    /** Schema JSON 落盘目录；支持 ~ 前缀 */
    private String storagePath = "~/.zont/ontologies";

    /** 查询与物化未指定 workspace 时的默认值 */
    private String defaultWorkspace = "default";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getDefaultWorkspace() { return defaultWorkspace; }
    public void setDefaultWorkspace(String defaultWorkspace) { this.defaultWorkspace = defaultWorkspace; }

    public Path resolveStoragePath() {
        String configured = storagePath == null || storagePath.trim().isEmpty()
                ? "~/.zont/ontologies" : storagePath.trim();
        if (configured.startsWith("~")) {
            String home = System.getProperty("user.home");
            String rest = configured.substring(1);
            return rest.isEmpty() ? Paths.get(home) : Paths.get(home, rest);
        }
        return Paths.get(configured);
    }
}
