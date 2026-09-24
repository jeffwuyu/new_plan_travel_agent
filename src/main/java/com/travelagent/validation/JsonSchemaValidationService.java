package com.travelagent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class JsonSchemaValidationService {

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;

    public JsonSchemaValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    public JsonNode validateOrThrow(String validationTarget, Object payload, Map<String, Object> schema) {
        List<ValidationError> errors = validate(validationTarget, payload, schema);
        if (!errors.isEmpty()) {
            throw new JsonSchemaValidationException(validationTarget, errors);
        }
        return toJsonNode(payload);
    }

    public List<ValidationError> validate(String validationTarget, Object payload, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        JsonNode schemaNode = toJsonNode(schema);
        JsonNode payloadNode = toJsonNode(payload == null ? Map.of() : payload);
        JsonSchema jsonSchema = schemaFactory.getSchema(schemaNode);
        Set<ValidationMessage> messages = jsonSchema.validate(payloadNode);
        return messages.stream()
                .map(message -> new ValidationError(
                        normalizePath(message.getInstanceLocation() == null
                                ? null
                                : message.getInstanceLocation().toString()),
                        message.getMessage(),
                        message.getCode(),
                        message.getInstanceNode()))
                .sorted(Comparator.comparing(ValidationError::path)
                        .thenComparing(ValidationError::message))
                .toList();
    }

    public JsonNode toJsonNode(Object value) {
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return objectMapper.valueToTree(value);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "$".equals(path)) {
            return "$";
        }
        return path.startsWith("$") ? path : "$" + path;
    }

    public record ValidationError(String path, String message, String code, JsonNode rejectedValue) {
    }
}
