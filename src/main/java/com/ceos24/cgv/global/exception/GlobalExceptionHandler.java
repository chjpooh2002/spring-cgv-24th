package com.ceos24.cgv.global.exception;

import com.ceos24.cgv.global.response.ApiResponse;
import com.ceos24.cgv.global.response.ApiResponse.FieldError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustom(CustomException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[CustomException] {}: {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.getHttpStatus())
                             .body(ApiResponse.error(code));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        return invalidInput(e.getBindingResult().getFieldErrors().stream()
                .map(fe -> FieldError.of(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
                .toList());
    }

    // enum 쿼리 파라미터에 없는 값이 들어오면 바인딩 단계에서 터진다. 400이어야 할 오류다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[TypeMismatch] {}={}", e.getName(), e.getValue());
        return invalidInput(List.of(FieldError.of(e.getName(), e.getValue(), "허용되지 않은 값입니다.")));
    }

    // 필수 쿼리 파라미터 누락. 처리하지 않으면 아래 Exception 핸들러로 떨어져 500이 된다.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[MissingParam] {}", e.getParameterName());
        return invalidInput(List.of(FieldError.of(e.getParameterName(), null, "필수 파라미터입니다.")));
    }

    // JSON 문법 오류, 날짜 형식 오류, enum에 없는 값처럼 본문을 객체로 바꾸지 못한 경우.
    // 처리하지 않으면 아래 Exception 핸들러로 떨어져 500이 된다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[NotReadable] {}", e.getMessage());
        return ResponseEntity.badRequest()
                             .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("[Unhandled] ", e);
        return ResponseEntity.internalServerError()
                             .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    private ResponseEntity<ApiResponse<Void>> invalidInput(List<FieldError> fieldErrors) {
        return ResponseEntity.badRequest()
                             .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, fieldErrors));
    }
}
