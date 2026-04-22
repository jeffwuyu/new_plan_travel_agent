package com.travelagent.exception;

import com.travelagent.model.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLSyntaxErrorException;
import java.util.stream.Collectors;

/**
 * Global exception handler for all REST controllers.
 * Returns unified Result<T> structure for every error type.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business exception: [{}] {}", ex.getHttpStatus(), ex.getMessage());
        return ResponseEntity
            .status(ex.getHttpStatus())
            .body(Result.error(ex.getHttpStatus(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        log.warn("Validation error: {}", message);
        return ResponseEntity.badRequest().body(Result.badRequest(message));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.badRequest(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleGenericException(Exception ex) {
        Throwable rootCause = findRootCause(ex);
        String rootSummary = summarizeException(rootCause);
        if (isSchemaIssue(ex, rootCause)) {
            log.error("Unexpected exception: database schema not initialized or incompatible - {}", rootSummary, ex);
        } else {
            log.error("Unexpected exception: {}", rootSummary, ex);
        }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Result.serverError("系统异常，请稍后重试"));
    }

    private boolean isSchemaIssue(Exception ex, Throwable rootCause) {
        if (ex instanceof BadSqlGrammarException || rootCause instanceof SQLSyntaxErrorException) {
            String message = rootCause != null ? rootCause.getMessage() : ex.getMessage();
            if (message == null) {
                return true;
            }
            String normalized = message.toLowerCase();
            return normalized.contains("unknown column")
                || normalized.contains("doesn't exist")
                || normalized.contains("does not exist")
                || normalized.contains("bad sql grammar");
        }
        return false;
    }

    private Throwable findRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String summarizeException(Throwable throwable) {
        if (throwable == null) {
            return "unknown root cause";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }
}
