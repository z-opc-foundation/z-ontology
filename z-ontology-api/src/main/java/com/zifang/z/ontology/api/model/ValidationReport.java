package com.zifang.z.ontology.api.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验报告。没有 ERROR 即视为通过。
 */
public class ValidationReport {

    private List<ValidationIssue> issues = new ArrayList<>();

    public ValidationReport() {
    }

    public ValidationReport(List<ValidationIssue> issues) {
        this.issues = issues == null ? new ArrayList<>() : issues;
    }

    public boolean isValid() {
        for (ValidationIssue issue : issues) {
            if (issue.getSeverity() == IssueSeverity.ERROR) return false;
        }
        return true;
    }

    public long count(IssueSeverity severity) {
        long n = 0;
        for (ValidationIssue issue : issues) {
            if (issue.getSeverity() == severity) n++;
        }
        return n;
    }

    public long getErrorCount() { return count(IssueSeverity.ERROR); }
    public long getWarningCount() { return count(IssueSeverity.WARNING); }
    public long getInfoCount() { return count(IssueSeverity.INFO); }

    public List<ValidationIssue> getIssues() { return issues; }
    public void setIssues(List<ValidationIssue> issues) {
        this.issues = issues == null ? new ArrayList<>() : issues;
    }
}
