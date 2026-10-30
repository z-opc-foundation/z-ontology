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
import java.util.PriorityQueue;
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
 * <p>传递闭包按谓词求「最宽路径」（瓶颈权重最大）一次到位，不生成自环推断；
 * 其余规则仍走不动点迭代。</p>
 *
 * <p>不做同等性（sameAs）与 OWL 复杂公理；功能性属性只在校验阶段报冲突。</p>
 */
public class RdfsReasoner {

    private static final int MAX_ROUNDS = 1000;
    private static final int UNCHANGED = 0;
    private static final int INSERTED = 1;
    private static final int UPDATED = 2;

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
        boolean closureNeeded = true;
        do {
            changed = false;
            boolean statementsTouched = false;

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
                    if (addStatement(statements, derived) != UNCHANGED) {
                        changed = true;
                        statementsTouched = true;
                    }
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
                if (addStatement(statements, reverse) != UNCHANGED) {
                    changed = true;
                    statementsTouched = true;
                }
            }

            // 闭包本身已对全部传递谓词求到不动点；没有新语句（或权重变化）时无需重算。
            if (closureNeeded || statementsTouched) {
                if (closeTransitive(schema, statements) != UNCHANGED) changed = true;
            }
            closureNeeded = false;

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

    /**
     * 传递闭包：按谓词建邻接表，对每个起点做最宽路径（max-min）搜索。
     *
     * <p>旧的「每轮把全部语句两两配对再取 min 权重」在长链上是 O(n³)；
     * 这里直接求最宽路径不动点，权重语义不变（路径权重 = 各段最小，
     * 多条路径取最大），轮数从 O(n) 降到 1。</p>
     */
    private int closeTransitive(OntologySchema schema, Map<TripleKey, Statement> statements) {
        Map<String, Map<String, List<Statement>>> adjacency = new LinkedHashMap<>();
        for (Statement s : statements.values()) {
            if (s.isLiteral() || s.getSubject() == null || s.getObject() == null) continue;
            OntologyProperty p = schema.findProperty(s.getPredicate());
            if (p == null || p.getKind() != PropertyKind.OBJECT || !p.isTransitive()) continue;
            adjacency.computeIfAbsent(s.getPredicate(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(s.getSubject(), k -> new ArrayList<>())
                    .add(s);
        }

        int changed = UNCHANGED;
        for (Map.Entry<String, Map<String, List<Statement>>> entry : adjacency.entrySet()) {
            String predicate = entry.getKey();
            Map<String, List<Statement>> edges = entry.getValue();
            for (String start : edges.keySet()) {
                Map<String, String> parents = new LinkedHashMap<>();
                Map<String, Statement> incoming = new LinkedHashMap<>();
                Map<String, Double> reachable = widestReach(start, edges, parents, incoming);
                for (Map.Entry<String, Double> target : reachable.entrySet()) {
                    if (target.getKey().equals(start)) continue;
                    Statement derived = new Statement(start, predicate, target.getKey(), false);
                    derived.setWeight(target.getValue());
                    derived.getSources().addAll(pathSources(start, target.getKey(), parents, incoming));
                    int added = addStatement(statements, derived);
                    if (added == INSERTED) {
                        changed = INSERTED;
                    } else if (added == UPDATED && changed == UNCHANGED) {
                        changed = UPDATED;
                    }
                }
            }
        }
        return changed;
    }

    /** 从 start 出发的最宽路径（瓶颈最大）可达集合，同时记录父指针用于回溯证据。 */
    private static Map<String, Double> widestReach(String start, Map<String, List<Statement>> edges,
                                                   Map<String, String> parents, Map<String, Statement> incoming) {
        Map<String, Double> best = new LinkedHashMap<>();
        Set<String> settled = new LinkedHashSet<>();
        PriorityQueue<PathNode> queue = new PriorityQueue<>((a, b) -> Double.compare(b.weight, a.weight));
        queue.add(new PathNode(start, Double.POSITIVE_INFINITY));
        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (!settled.add(current.key)) continue;
            for (Statement edge : edges.getOrDefault(current.key, List.of())) {
                String next = edge.getObject();
                if (settled.contains(next)) continue;
                double candidate = Math.min(current.weight, edge.getWeight());
                Double known = best.get(next);
                if (known != null && known >= candidate) continue;
                best.put(next, candidate);
                parents.put(next, current.key);
                incoming.put(next, edge);
                queue.add(new PathNode(next, candidate));
            }
        }
        return best;
    }

    private static List<String> pathSources(String start, String target,
                                            Map<String, String> parents, Map<String, Statement> incoming) {
        Set<String> sources = new LinkedHashSet<>();
        String node = target;
        Set<String> seen = new LinkedHashSet<>();
        while (node != null && !node.equals(start) && seen.add(node)) {
            Statement edge = incoming.get(node);
            if (edge == null) break;
            sources.addAll(edge.getSources());
            node = parents.get(node);
        }
        return new ArrayList<>(sources);
    }

    private static final class PathNode {
        private final String key;
        private final double weight;

        private PathNode(String key, double weight) {
            this.key = key;
            this.weight = weight;
        }
    }

    private static int addStatement(Map<TripleKey, Statement> statements, Statement derived) {
        TripleKey key = new TripleKey(derived);
        // 派生语句在构造时 inferred 还是默认 false；先标成推断，merge 才不会把它当显式语句
        // 把已有推导结果降级成 explicit=false。
        derived.setInferred(true);
        Statement existing = statements.get(key);
        if (existing != null) {
            return merge(existing, derived) ? UPDATED : UNCHANGED;
        }
        statements.put(key, derived);
        return INSERTED;
    }

    private static boolean merge(Statement existing, Statement incoming) {
        boolean raised = incoming.getWeight() > existing.getWeight();
        existing.setWeight(Math.max(existing.getWeight(), incoming.getWeight()));
        if (!incoming.isInferred()) existing.setInferred(false);
        Set<String> sources = new LinkedHashSet<>(existing.getSources());
        sources.addAll(incoming.getSources());
        existing.setSources(new ArrayList<>(sources));
        return raised;
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
