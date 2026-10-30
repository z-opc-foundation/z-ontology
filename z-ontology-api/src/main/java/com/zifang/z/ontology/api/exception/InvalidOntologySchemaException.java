package com.zifang.z.ontology.api.exception;

import com.zifang.z.ontology.api.model.ValidationReport;

/**
 * 本体 Schema 非法（引用缺失、环、domain/range 指错等）。
 */
public class InvalidOntologySchemaException extends OntologyException {

    private final ValidationReport report;

    public InvalidOntologySchemaException(String message, ValidationReport report) {
        super(message);
        this.report = report;
    }

    public ValidationReport getReport() {
        return report;
    }
}
