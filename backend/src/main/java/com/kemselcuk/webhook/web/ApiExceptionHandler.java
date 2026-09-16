package com.kemselcuk.webhook.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().stream()
                .limit(20)
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "One or more request fields are invalid.",
                "VALIDATION_ERROR",
                fieldErrors
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getConstraintViolations().stream()
                .limit(20)
                .forEach(violation -> fieldErrors.putIfAbsent(
                        propertyName(violation), violation.getMessage()));
        return response(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "One or more request fields are invalid.",
                "VALIDATION_ERROR",
                fieldErrors
        );
    }

    @ExceptionHandler(ApiRequestValidationException.class)
    ResponseEntity<ProblemDetail> handleRequestValidation(ApiRequestValidationException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "One or more request fields are invalid.",
                "VALIDATION_ERROR",
                exception.getFieldErrors()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleMalformedJson() {
        return response(
                HttpStatus.BAD_REQUEST,
                "Malformed request",
                "The request body is not valid JSON for this endpoint.",
                "MALFORMED_JSON",
                Map.of()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "A request parameter has an invalid value.",
                "VALIDATION_ERROR",
                Map.of(exception.getName(), "parameter has an invalid value")
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> handleMissingRequestParameter(
            MissingServletRequestParameterException exception
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "A required request parameter is missing.",
                "VALIDATION_ERROR",
                Map.of(exception.getParameterName(), "parameter is required")
        );
    }

    @ExceptionHandler(InvalidEndpointUrlException.class)
    ResponseEntity<ProblemDetail> handleInvalidEndpointUrl() {
        return response(
                HttpStatus.BAD_REQUEST,
                "Invalid endpoint URL",
                "url must be an absolute HTTP or HTTPS URL with a host and without user-info or a fragment.",
                "VALIDATION_ERROR",
                Map.of("url", "must be a valid absolute HTTP or HTTPS URL")
        );
    }

    @ExceptionHandler(EndpointNameConflictException.class)
    ResponseEntity<ProblemDetail> handleEndpointNameConflict() {
        return response(
                HttpStatus.CONFLICT,
                "Endpoint name already exists",
                "An endpoint with this name already exists.",
                "ENDPOINT_NAME_CONFLICT",
                Map.of()
        );
    }

    @ExceptionHandler(EndpointNotFoundException.class)
    ResponseEntity<ProblemDetail> handleEndpointNotFound() {
        return response(
                HttpStatus.NOT_FOUND,
                "Endpoint not found",
                "One or more requested webhook endpoints do not exist.",
                "ENDPOINT_NOT_FOUND",
                Map.of()
        );
    }

    @ExceptionHandler(DisabledEndpointException.class)
    ResponseEntity<ProblemDetail> handleDisabledEndpoint() {
        return response(
                HttpStatus.CONFLICT,
                "Endpoint disabled",
                "One or more requested webhook endpoints are disabled.",
                "ENDPOINT_DISABLED",
                Map.of()
        );
    }

    private static String propertyName(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 ? path.substring(lastDot + 1) : path;
    }

    private static ResponseEntity<ProblemDetail> response(
            HttpStatus status,
            String title,
            String detail,
            String code,
            Map<String, String> fieldErrors
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        if (!fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        return ResponseEntity.status(status).body(problem);
    }
}
