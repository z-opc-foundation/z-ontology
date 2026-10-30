package com.zifang.z.ontology.core.support;

import com.zifang.z.ontology.api.model.OntologyClass;
import com.zifang.z.ontology.api.model.OntologyProperty;
import com.zifang.z.ontology.api.model.OntologySchema;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 类层级与属性层级的闭包查询。所有方法都基于名称（本体内唯一）。
 */
public class ClassHierarchy {

    private final OntologySchema schema;
    private final Map<String, OntologyClass> classes = new LinkedHashMap<>();
    private final Map<String, OntologyProperty> properties = new LinkedHashMap<>();

    public ClassHierarchy(OntologySchema schema) {
        this.schema = schema;
        for (OntologyClass c : schema.getClasses()) classes.put(c.getName(), c);
        for (OntologyProperty p : schema.getProperties()) properties.put(p.getName(), p);
    }

    public OntologySchema schema() {
        return schema;
    }

    public OntologyClass clazz(String name) {
        return classes.get(name);
    }

    public OntologyProperty property(String name) {
        return properties.get(name);
    }

    public List<String> parents(String className) {
        OntologyClass c = classes.get(className);
        return c == null ? Collections.emptyList() : c.getSuperClasses();
    }

    /** 不含自身的全部祖先。 */
    public Set<String> ancestors(String className) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(parents(className));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !result.add(current)) continue;
            queue.addAll(parents(current));
        }
        return result;
    }

    public boolean isSubclassOf(String child, String parent) {
        if (child == null || parent == null) return false;
        if (child.equals(parent)) return true;
        return ancestors(child).contains(parent);
    }

    /** 子类声明会继承父类的 disjointWith；两个方向都查。 */
    public boolean isDisjoint(String a, String b) {
        if (a == null || b == null || a.equals(b)) return false;
        Set<String> left = new LinkedHashSet<>();
        left.add(a);
        left.addAll(ancestors(a));
        Set<String> right = new LinkedHashSet<>();
        right.add(b);
        right.addAll(ancestors(b));
        for (String x : left) {
            OntologyClass cx = classes.get(x);
            if (cx == null) continue;
            for (String d : cx.getDisjointWith()) {
                if (right.contains(d)) return true;
            }
        }
        return false;
    }

    /** 属性父链闭包（不含自身）。 */
    public Set<String> superProperties(String propertyName) {
        Set<String> result = new LinkedHashSet<>();
        OntologyProperty p = properties.get(propertyName);
        if (p == null) return result;
        Deque<String> queue = new ArrayDeque<>(p.getSuperProperties());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !result.add(current)) continue;
            OntologyProperty sp = properties.get(current);
            if (sp != null) queue.addAll(sp.getSuperProperties());
        }
        return result;
    }

    /**
     * 找环：返回第一个环的路径（含重复的起点），无环返回空列表。
     */
    public List<String> findClassCycle() {
        Map<String, Integer> state = new LinkedHashMap<>();
        for (String name : classes.keySet()) {
            List<String> cycle = dfsClass(name, state, new ArrayList<>());
            if (!cycle.isEmpty()) return cycle;
        }
        return Collections.emptyList();
    }

    private List<String> dfsClass(String name, Map<String, Integer> state, List<String> path) {
        Integer s = state.get(name);
        if (s != null) {
            if (s == 1) {
                List<String> cycle = new ArrayList<>();
                int idx = path.indexOf(name);
                if (idx >= 0) {
                    cycle.addAll(path.subList(idx, path.size()));
                    cycle.add(name);
                }
                return cycle;
            }
            return Collections.emptyList();
        }
        state.put(name, 1);
        path.add(name);
        for (String parent : parents(name)) {
            List<String> cycle = dfsClass(parent, state, path);
            if (!cycle.isEmpty()) return cycle;
        }
        path.remove(path.size() - 1);
        state.put(name, 2);
        return Collections.emptyList();
    }

    public List<String> findPropertyCycle() {
        Map<String, Integer> state = new LinkedHashMap<>();
        for (String name : properties.keySet()) {
            List<String> cycle = dfsProperty(name, state, new ArrayList<>());
            if (!cycle.isEmpty()) return cycle;
        }
        return Collections.emptyList();
    }

    private List<String> dfsProperty(String name, Map<String, Integer> state, List<String> path) {
        Integer s = state.get(name);
        if (s != null) {
            if (s == 1) {
                List<String> cycle = new ArrayList<>();
                int idx = path.indexOf(name);
                if (idx >= 0) {
                    cycle.addAll(path.subList(idx, path.size()));
                    cycle.add(name);
                }
                return cycle;
            }
            return Collections.emptyList();
        }
        state.put(name, 1);
        path.add(name);
        OntologyProperty p = properties.get(name);
        if (p != null) {
            for (String parent : p.getSuperProperties()) {
                List<String> cycle = dfsProperty(parent, state, path);
                if (!cycle.isEmpty()) return cycle;
            }
        }
        path.remove(path.size() - 1);
        state.put(name, 2);
        return Collections.emptyList();
    }
}
