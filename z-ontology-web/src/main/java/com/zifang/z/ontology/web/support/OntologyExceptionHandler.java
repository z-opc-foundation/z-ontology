package com.zifang.z.ontology.web.support;

import com.zifang.z.ontology.api.exception.InvalidOntologySchemaException;
import com.zifang.z.ontology.api.exception.OntologyException;
import com.zifang.z.ontology.api.exception.OntologyNotFoundException;
import com.zifang.z.ontology.web.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 本体异常到 HTTP 状态的映射：找不到 404、Schema 非法 400（带校验报告）、其他本体错误 400。
 */
@RestControllerAdvice
public class OntologyExceptionHandler {

    @ExceptionHandler(OntologyNotFoundException.class)
    public ResponseEntity<ApiError> notFound(OntologyNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("ONTOLOGY_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(InvalidOntologySchemaException.class)
    public ResponseEntity<ApiError> invalidSchema(InvalidOntologySchemaException ex) {
        ApiError error = ApiError.of("INVALID_ONTOLOGY_SCHEMA", ex.getMessage());
        error.setValidation(ex.getReport());
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(OntologyException.class)
    public ResponseEntity<ApiError> ontologyError(OntologyException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("ONTOLOGY_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_ARGUMENT", ex.getMessage()));
    }
}
