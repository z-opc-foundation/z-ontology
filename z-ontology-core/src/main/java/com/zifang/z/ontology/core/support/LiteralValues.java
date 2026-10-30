package com.zifang.z.ontology.core.support;

import com.zifang.z.ontology.api.model.DataTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 字面量与数据属性 range 的转换/校验。
 */
public final class LiteralValues {

    private LiteralValues() {
    }

    /** 返回可读的错误；合法返回 null。 */
    public static String typeError(String value, String range) {
        if (value == null || range == null || DataTypes.ANY.equals(range) || DataTypes.STRING.equals(range)) {
            return null;
        }
        try {
            switch (range) {
                case DataTypes.INT:
                    Integer.parseInt(value);
                    return null;
                case DataTypes.LONG:
                    Long.parseLong(value);
                    return null;
                case DataTypes.DOUBLE:
                    Double.parseDouble(value);
                    return null;
                case DataTypes.BOOLEAN:
                    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return null;
                    return "expected BOOLEAN but got '" + value + "'";
                case DataTypes.DATE:
                    LocalDate.parse(value);
                    return null;
                case DataTypes.DATETIME:
                    try {
                        Instant.parse(value);
                    } catch (DateTimeParseException e) {
                        LocalDateTime.parse(value);
                    }
                    return null;
                default:
                    return null;
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            return "expected " + range + " but got '" + value + "'";
        }
    }

    /** 按 range 把字面量转成可写入图属性的类型；无法转换时保留字符串。 */
    public static Object convert(String value, String range) {
        if (value == null) return null;
        if (range == null) return value;
        try {
            switch (range) {
                case DataTypes.INT:
                    return Integer.parseInt(value);
                case DataTypes.LONG:
                    return Long.parseLong(value);
                case DataTypes.DOUBLE:
                    return Double.parseDouble(value);
                case DataTypes.BOOLEAN:
                    return Boolean.parseBoolean(value);
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
