package com.zifang.z.ontology.core.reasoning;

import com.zifang.z.ontology.api.exception.OntologyException;
import com.zifang.z.ontology.api.model.InferenceResult;
import com.zifang.z.ontology.api.model.Individual;
import com.zifang.z.ontology.api.model.OntologyProperty;
import com.zifang.z.ontology.api.model.OntologySchema;
import com.zifang.z.ontology.api.model.PropertyKind;
import com.zifang.z.ontology.api.model.Statement;
import com.zifang.z.ontology.core.support.ClassHierarchy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RDFS 子集推理（前向链，不动点迭代）：
 * <ul>
 *   <li>subClassOf 传递闭包 → 个体类型继承</li>
 *   <li>subPropertyOf → 断言扩展</li>
 *   <li>domain / range → 个体类型推断</li>
 *   <li>inverseOf / symmetric → 反向断言</li>
 *   <li>transitive → 传递闭包</li>
 * </ul>
 *
 * <p>不做同等性（sameAs）与 OWL 复杂公理；功能性属性只在校验阶段报冲突。</p>
 */
public class RdfsReasoner {

    private static final int MAX_ROUNDS = 1000;

    public InferenceResult infer(OntologySchema schema, List<Individual> inputIndividuals,
                                 List<Statement> inputStatements) {
        ClassHierarchy hierarchy = new ClassHierarchy(schema);
        Map<String, Individual> individuals = copyIndividuals(inputIndividuals);
        Map<TripleKey, Statement> statements = new LinkedHashMap<>();
        if (inputStatements != null) {
            for (Statement s : inputStatements) {
                if (s == null || isBlank(s.getSubject()) || isBlank(s.getPredicate())) continue;
                Statement copy = copyStatement(s);
                copy.setInferred(false);
                TripleKey key = new TripleKey(copy);
                Statement existing = statements.get(key);
                if (existing == null) {
                    statements.put(key, copy);
                } else {
                    merge(existing, copy);
                }
            }
        }

        int explicitTypes = 0;
        for (Individual ind : individuals.values()) explicitTypes += ind.getTypes().size();

        int rounds = 0;
        boolean changed;
        do {
            changed = false;

            for (Individual ind : individuals.values()) {
                for (String type : new ArrayList<>(ind.allTypes())) {
                    for (String ancestor : hierarchy.ancestors(type)) {
                        if (!ind.getTypes().contains(ancestor) && ind.getInferredTypes().add(ancestor)) {
                            changed = true;
                        }
                    }
                }
            }

            for (Statement s : new ArrayList<>(statements.values())) {
                for (String superProperty : hierarchy.superProperties(s.getPredicate())) {
                    Statement derived = new Statement(s.getSubject(), superProperty, s.getObject(), s.isLiteral());
                    derived.setWeight(s.getWeight());
                    derived.getSources().addAll(s.getSources());
                    if (addStatement(statements, derived)) changed = true;
                }
            }

            for (Statement s : new ArrayList<>(statements.values())) {
                OntologyProperty p = schema.findProperty(s.getPredicate());
                if (p == null) continue;
                if (p.getDomain() != null && addType(individuals, s.getSubject(), p.getDomain())) {
                    changed = true;
                }
                if (!s.isLiteral() && p.getKind() == PropertyKind.OBJECT && p.getRange() != null
                        && addType(individuals, s.getObject(), p.getRange())) {
                    changed = true;
                }
            }

            for (Statement s : new ArrayList<>(statements.values())) {
                if (s.isLiteral()) continue;
                OntologyProperty p = schema.findProperty(s.getPredicate());
                if (p == null || p.getKind() != PropertyKind.OBJECT) continue;
                String inverse = p.getInverseOf();
                if (inverse == null && p.isSymmetric()) inverse = p.getName();
                if (inverse == null) continue;
                Statement reverse = new Statement(s.getObject(), inverse, s.getSubject(), false);
                reverse.setWeight(s.getWeight());
                reverse.getSources().addAll(s.getSources());
                if (addStatement(statements, reverse)) changed = true;
            }

            List<Statement> transitiveCandidates = new ArrayList<>(statements.values());
            for (Statement a : transitiveCandidates) {
                if (a.isLiteral()) continue;
                OntologyProperty p = schema.findProperty(a.getPredicate());
                if (p == null || p.getKind() != PropertyKind.OBJECT || !p.isTransitive()) continue;
                for (Statement b : transitiveCandidates) {
                    if (b.isLiteral() || !a.getPredicate().equals(b.getPredicate())) continue;
                    if (!a.getObject().equals(b.getSubject())) continue;
                    Statement derived = new Statement(a.getSubject(), a.getPredicate(), b.getObject(), false);
                    derived.setWeight(Math.min(a.getWeight(), b.getWeight()));
                    derived.getSources().addAll(a.getSources());
                    derived.getSources().addAll(b.getSources());
                    if (addStatement(statements, derived)) changed = true;
                }
            }

            rounds++;
        } while (changed && rounds < MAX_ROUNDS);

        if (changed) {
            throw new OntologyException("RDFS inference did not converge after " + MAX_ROUNDS + " rounds");
        }

        InferenceResult result = new InferenceResult();
        result.setIndividuals(new ArrayList<>(individuals.values()));
        result.setStatements(new ArrayList<>(statements.values()));
        int inferredTypes = 0;
        for (Individual ind : individuals.values()) inferredTypes += ind.getInferredTypes().size();
        int inferredStatements = 0;
        for (Statement s : statements.values()) {
            if (s.isInferred()) inferredStatements++;
        }
        result.setExplicitTypes(explicitTypes);
        result.setInferredTypes(inferredTypes);
        result.setExplicitStatements(statements.size() - inferredStatements);
        result.setInferredStatements(inferredStatements);
        return result;
    }

    private Map<String, Individual> copyIndividuals(List<Individual> input) {
        Map<String, Individual> result = new LinkedHashMap<>();
        if (input == null) return result;
        for (Individual source : input) {
            if (source == null) continue;
            String key = source.getKey();
            if (isBlank(key)) key = source.getName();
            if (isBlank(key)) continue;
            Individual copy = new Individual(key, source.getName() == null ? key : source.getName());
            copy.setLabel(source.getLabel());
            copy.setTypes(new LinkedHashSet<>(source.getTypes()));
            copy.setProperties(new LinkedHashMap<>(source.getProperties()));
            copy.setSources(new ArrayList<>(source.getSources()));
            result.put(key, copy);
        }
        return result;
    }

    private static Statement copyStatement(Statement source) {
        Statement copy = new Statement(source.getSubject(), source.getPredicate(), source.getObject(), source.isLiteral());
        copy.setWeight(source.getWeight());
        copy.setInferred(source.isInferred());
        copy.setSources(new ArrayList<>(source.getSources()));
        return copy;
    }

    private static boolean addStatement(Map<TripleKey, Statement> statements, Statement derived) {
        TripleKey key = new TripleKey(derived);
        // 派生语句在构造时 inferred 还是默认 false；先标成推断，merge 才不会把它当显式语句
        // 把已有推导结果降级成 explicit=false。
        derived.setInferred(true);
        Statement existing = statements.get(key);
        if (existing != null) {
            merge(existing, derived);
            return false;
        }
        statements.put(key, derived);
        return true;
    }

    private static void merge(Statement existing, Statement incoming) {
        existing.setWeight(Math.max(existing.getWeight(), incoming.getWeight()));
        if (!incoming.isInferred()) existing.setInferred(false);
        Set<String> sources = new LinkedHashSet<>(existing.getSources());
        sources.addAll(incoming.getSources());
        existing.setSources(new ArrayList<>(sources));
    }

    private static boolean addType(Map<String, Individual> individuals, String key, String className) {
        Individual ind = individuals.get(key);
        if (ind == null) return false;
        if (ind.getTypes().contains(className) || ind.getInferredTypes().contains(className)) return false;
        ind.getInferredTypes().add(className);
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static final class TripleKey {
        private final String subject;
        private final String predicate;
        private final String object;
        private final boolean literal;

        private TripleKey(Statement s) {
            this.subject = s.getSubject();
            this.predicate = s.getPredicate();
            this.object = s.getObject();
            this.literal = s.isLiteral();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TripleKey)) return false;
            TripleKey that = (TripleKey) o;
            return literal == that.literal
                    && Objects.equals(subject, that.subject)
                    && Objects.equals(predicate, that.predicate)
                    && Objects.equals(object, that.object);
        }

        @Override
        public int hashCode() {
            return Objects.hash(subject, predicate, object, literal);
        }
    }
}
