package io.github.lalathomas.walletledger.common.api;

import io.github.lalathomas.walletledger.wallet.application.WalletErrorCode;
import io.github.lalathomas.walletledger.wallet.application.WalletException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletException.class)
    public ResponseEntity<ApiErrorResponse> handleWalletException(
            WalletException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = statusFor(exception.getCode());
        return ResponseEntity.status(status).body(errorBody(
                status,
                exception.getCode().name(),
                exception.getMessage(),
                request.getRequestURI(),
                Map.of(),
                exception.getDetails()
        ));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleLockFailure(
            PessimisticLockingFailureException exception,
            HttpServletRequest request
    ) {
        log.warn("Could not acquire wallet lock for path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(errorBody(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "WALLET_BUSY",
                        "The wallet is busy; retry the request with the same idempotency key",
                        request.getRequestURI(),
                        Map.of(),
                        Map.of()
                ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedConstraintViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        log.error("Unexpected database constraint violation for path={}", request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(
                HttpStatus.CONFLICT,
                "DATABASE_CONSTRAINT_VIOLATION",
                "The request conflicts with existing data",
                request.getRequestURI(),
                Map.of(),
                Map.of()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unhandled request failure for path={}", request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                request.getRequestURI(),
                Map.of(),
                Map.of()
        ));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                fieldErrors.putIfAbsent("request", error.getDefaultMessage())
        );

        return ResponseEntity.badRequest().body(errorBody(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                pathFrom(request),
                fieldErrors,
                Map.of()
        ));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return ResponseEntity.badRequest().body(errorBody(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Request body is missing or contains invalid JSON",
                pathFrom(request),
                Map.of(),
                Map.of()
        ));
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            org.springframework.web.bind.ServletRequestBindingException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return ResponseEntity.badRequest().body(errorBody(
                HttpStatus.BAD_REQUEST,
                "MISSING_REQUEST_VALUE",
                "A required header, query parameter, or path value is missing",
                pathFrom(request),
                Map.of(),
                Map.of()
        ));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return ResponseEntity.badRequest().body(errorBody(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "A path or query parameter has an invalid value",
                pathFrom(request),
                Map.of(),
                Map.of()
        ));
    }

    private static HttpStatus statusFor(WalletErrorCode code) {
        return switch (code) {
            case WALLET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case WALLET_ALREADY_EXISTS, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case INSUFFICIENT_FUNDS, BALANCE_OVERFLOW -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_AMOUNT, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
    }

    private static ApiErrorResponse errorBody(
            HttpStatus status,
            String code,
            String message,
            String path,
            Map<String, String> fieldErrors,
            Map<String, Object> details
    ) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                path,
                fieldErrors,
                details
        );
    }

    private static String pathFrom(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return "";
    }
}
