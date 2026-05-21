package com.jts.gjcxfzksh.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 业务异常
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BusinessException extends RuntimeException{

    String code = "-1";

    String message;
    public BusinessException(String message){
        this.message = message;
    }


    public BusinessException(String message, Exception cause){
        this.message = message;
        this.initCause(cause);
    }

}
