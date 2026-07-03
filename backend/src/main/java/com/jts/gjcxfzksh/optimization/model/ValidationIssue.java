package com.jts.gjcxfzksh.optimization.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationIssue {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";

    /** error | warning */
    private String level;
    /** 关联修改项 id，可为空（全局问题） */
    private String editId;
    private String message;

    public static ValidationIssue error(String editId, String message) {
        return new ValidationIssue(ERROR, editId, message);
    }

    public static ValidationIssue warning(String editId, String message) {
        return new ValidationIssue(WARNING, editId, message);
    }
}
