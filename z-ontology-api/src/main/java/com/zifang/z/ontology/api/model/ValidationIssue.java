package com.zifang.z.ontology.api.model;

/**
 * 一条校验问题。code 是稳定判据（如 UNKNOWN_CLASS / DISJOINT_VIOLATION）。
 */
public class ValidationIssue {

    private IssueSeverity severity;
    private String code;
    private String subject;
    private String predicate;
    private String message;

    public ValidationIssue() {
    }

    public ValidationIssue(IssueSeverity severity, String code, String subject, String predicate, String message) {
        this.severity = severity;
        this.code = code;
        this.subject = subject;
        this.predicate = predicate;
        this.message = message;
    }

    public static ValidationIssue error(String code, String subject, String message) {
        return new ValidationIssue(IssueSeverity.ERROR, code, subject, null, message);
    }

    public static ValidationIssue warning(String code, String subject, String message) {
        return new ValidationIssue(IssueSeverity.WARNING, code, subject, null, message);
    }

    public static ValidationIssue info(String code, String subject, String message) {
        return new ValidationIssue(IssueSeverity.INFO, code, subject, null, message);
    }

    public IssueSeverity getSeverity() { return severity; }
    public void setSeverity(IssueSeverity severity) { this.severity = severity; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    @Override
    public String toString() {
        return severity + " " + code + " " + subject + (predicate == null ? "" : "." + predicate) + ": " + message;
    }
}
