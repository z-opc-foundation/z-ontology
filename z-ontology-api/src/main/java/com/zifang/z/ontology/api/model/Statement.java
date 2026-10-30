package com.zifang.z.ontology.api.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条断言（三元组）。object 是目标个体的 key 或字面量字符串；literal=true 表示字面量。
 */
public class Statement {

    private String subject;
    private String predicate;
    private String object;
    private boolean literal;
    private double weight = 1.0;
    private boolean inferred;
    private List<String> sources = new ArrayList<>();

    public Statement() {
    }

    public Statement(String subject, String predicate, String object, boolean literal) {
        this.subject = subject;
        this.predicate = predicate;
        this.object = object;
        this.literal = literal;
    }

    public static Statement of(String subject, String predicate, String object) {
        return new Statement(subject, predicate, object, false);
    }

    public static Statement literal(String subject, String predicate, Object value) {
        return new Statement(subject, predicate, value == null ? null : String.valueOf(value), true);
    }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }
    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }
    public boolean isLiteral() { return literal; }
    public void setLiteral(boolean literal) { this.literal = literal; }
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
    public boolean isInferred() { return inferred; }
    public void setInferred(boolean inferred) { this.inferred = inferred; }
    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) {
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
    }
}
