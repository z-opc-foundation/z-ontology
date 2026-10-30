package com.zifang.z.ontology.web.dto;

import com.zifang.z.ontology.api.model.ValidationReport;

/**
 * 统一错误体。validation 只在 Schema 校验失败等场景出现。
 */
public class ApiError {

    private String error;
    private String message;
    private ValidationReport validation;

    public ApiError() {
    }

    public ApiError(String error, String message) {
        this.error = error;
        this.message = message;
    }

    public static ApiError of(String error, String message) {
        return new ApiError(error, message);
    }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public ValidationReport getValidation() { return validation; }
    public void setValidation(ValidationReport validation) { this.validation = validation; }
}
