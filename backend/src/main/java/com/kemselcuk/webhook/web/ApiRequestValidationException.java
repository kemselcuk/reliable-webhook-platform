package com.kemselcuk.webhook.web;

import java.util.Map;

public class ApiRequestValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ApiRequestValidationException(String field, String message) {
        this.fieldErrors = Map.of(field, message);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
