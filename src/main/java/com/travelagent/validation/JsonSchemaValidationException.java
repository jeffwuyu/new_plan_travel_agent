package com.travelagent.validation;

import java.util.List;

public class JsonSchemaValidationException extends RuntimeException {

    private final String validationTarget;
    private final List<JsonSchemaValidationService.ValidationError> errors;

    public JsonSchemaValidationException(String validationTarget,
                                         List<JsonSchemaValidationService.ValidationError> errors) {
        super(buildMessage(validationTarget, errors));
        this.validationTarget = validationTarget;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public String getValidationTarget() {
        return validationTarget;
    }

    public List<JsonSchemaValidationService.ValidationError> getErrors() {
        return errors;
    }

    private static String buildMessage(String validationTarget,
                                       List<JsonSchemaValidationService.ValidationError> errors) {
        String target = validationTarget == null || validationTarget.isBlank()
                ? "JSON payload"
                : validationTarget;
        if (errors == null || errors.isEmpty()) {
            return target + " failed JSON schema validation";
        }
        return target + " failed JSON schema validation: " + errors.stream()
                .map(error -> error.path() + " " + error.message())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }
}
