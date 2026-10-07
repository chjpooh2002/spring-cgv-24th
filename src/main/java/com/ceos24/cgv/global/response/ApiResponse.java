package com.ceos24.cgv.global.response;

import com.ceos24.cgv.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;
    private final List<FieldError> errors;

    @Getter
    public static class FieldError {
        private final String field;
        private final String value;
        private final String reason;

        private FieldError(String field, String value, String reason) {
            this.field = field;
            this.value = value;
            this.reason = reason;
        }

        // 거부된 값은 타입이 제각각이라 Object로 받아 문자열로 바꾼다. 값이 없으면 응답에서 빠지도록 null로 둔다.
        public static FieldError of(String field, Object value, String reason) {
            return new FieldError(field, Objects.toString(value, null), reason);
        }
    }

    private static final String SUCCESS_CODE = "SUCCESS";
    private static final String SUCCESS_MESSAGE = "요청이 성공했습니다.";

    private ApiResponse(boolean success, String code, String message, T data, List<FieldError> errors) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
        this.errors = errors;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, data, null);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, null, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.name(), errorCode.getMessage(), null, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, List<FieldError> errors) {
        return new ApiResponse<>(false, errorCode.name(), errorCode.getMessage(), null, errors);
    }
}
