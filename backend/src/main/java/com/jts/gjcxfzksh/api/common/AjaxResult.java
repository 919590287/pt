package com.jts.gjcxfzksh.api.common;

import lombok.Data;

@Data
public class AjaxResult {

    public Object data;
    public String msg;
    public Integer code;

    public static AjaxResult ok() {
        AjaxResult result = new AjaxResult();
        result.data = null;
        result.msg = "success";
        result.code = 200;
        return result;
    }

    public static AjaxResult okError(boolean flag) {
        return flag ? ok() : error();
    }

    public static AjaxResult ok(Object data) {
        AjaxResult result = new AjaxResult();
        result.data = data;
        result.msg = "success";
        result.code = 200;
        return result;
    }

    public static AjaxResult error(String msg) {
        AjaxResult result = new AjaxResult();
        result.data = null;
        result.msg = msg;
        result.code = -1;
        return result;
    }

    public static AjaxResult error() {
        AjaxResult result = new AjaxResult();
        result.data = null;
        result.msg = "error";
        result.code = -1;
        return result;
    }

}