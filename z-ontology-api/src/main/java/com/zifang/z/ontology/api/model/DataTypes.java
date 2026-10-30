package com.zifang.z.ontology.api.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 数据属性可声明的字面量类型（与 z-graph 的类型子集对齐）。
 */
public final class DataTypes {

    public static final String STRING = "STRING";
    public static final String INT = "INT";
    public static final String LONG = "LONG";
    public static final String DOUBLE = "DOUBLE";
    public static final String BOOLEAN = "BOOLEAN";
    public static final String DATE = "DATE";
    public static final String DATETIME = "DATETIME";
    public static final String ANY = "ANY";

    public static final Set<String> SUPPORTED = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(STRING, INT, LONG, DOUBLE, BOOLEAN, DATE, DATETIME, ANY)));

    private DataTypes() {
    }
}
