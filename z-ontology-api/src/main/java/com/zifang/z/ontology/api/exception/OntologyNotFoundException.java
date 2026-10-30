package com.zifang.z.ontology.api.exception;

/**
 * 本体不存在。
 */
public class OntologyNotFoundException extends OntologyException {

    public OntologyNotFoundException(String ontologyId) {
        super("Ontology not found: " + ontologyId);
    }
}
