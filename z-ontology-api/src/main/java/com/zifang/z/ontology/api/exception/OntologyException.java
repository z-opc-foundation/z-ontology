package com.zifang.z.ontology.api.exception;

/**
 * 本体层异常基类。
 */
public class OntologyException extends RuntimeException {

    public OntologyException(String message) {
        super(message);
    }

    public OntologyException(String message, Throwable cause) {
        super(message, cause);
    }
}
