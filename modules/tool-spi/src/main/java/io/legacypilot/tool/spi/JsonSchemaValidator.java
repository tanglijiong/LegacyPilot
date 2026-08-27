package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A fail-closed validator for the JSON Schema subset used by LegacyPilot tool descriptors. */
public final class JsonSchemaValidator {

  public List<String> validate(JsonNode schema, JsonNode value) {
    var errors = new ArrayList<String>();
    validateAt(schema, value, "$", errors);
    return List.copyOf(errors);
  }

  private void validateAt(JsonNode schema, JsonNode value, String path, List<String> errors) {
    var type = schema.path("type").asText();
    if (!type.isEmpty() && !matches(type, value)) {
      errors.add(path + " must be " + type);
      return;
    }
    if ("object".equals(type)) {
      validateObject(schema, value, path, errors);
    } else if ("array".equals(type)) {
      validateArray(schema, value, path, errors);
    } else if ("string".equals(type)) {
      validateString(schema, value, path, errors);
    } else if (!type.isEmpty() && !Set.of("integer", "number", "boolean", "null").contains(type)) {
      errors.add(path + " uses unsupported schema type " + type);
    }
  }

  private void validateObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
    var required = schema.path("required");
    if (required.isArray()) {
      required.forEach(
          field -> {
            if (!value.has(field.asText())) {
              errors.add(path + "." + field.asText() + " is required");
            }
          });
    }
    var properties = schema.path("properties");
    value
        .properties()
        .forEach(
            entry -> {
              if (properties.has(entry.getKey())) {
                validateAt(
                    properties.get(entry.getKey()),
                    entry.getValue(),
                    path + "." + entry.getKey(),
                    errors);
              } else if (!schema.path("additionalProperties").asBoolean(true)) {
                errors.add(path + "." + entry.getKey() + " is not allowed");
              }
            });
  }

  private void validateArray(JsonNode schema, JsonNode value, String path, List<String> errors) {
    var maxItems = schema.path("maxItems").asInt(Integer.MAX_VALUE);
    if (value.size() > maxItems) {
      errors.add(path + " has too many items");
    }
    var items = schema.path("items");
    if (!items.isMissingNode()) {
      for (int index = 0; index < value.size(); index++) {
        validateAt(items, value.get(index), path + "[" + index + "]", errors);
      }
    }
  }

  private void validateString(JsonNode schema, JsonNode value, String path, List<String> errors) {
    var text = value.asText();
    if (text.length() > schema.path("maxLength").asInt(Integer.MAX_VALUE)) {
      errors.add(path + " is too long");
    }
    var pattern = schema.path("pattern").asText();
    if (!pattern.isEmpty() && !text.matches(pattern)) {
      errors.add(path + " does not match its allowed pattern");
    }
    var values = schema.path("enum");
    if (values.isArray()
        && !java.util.stream.StreamSupport.stream(values.spliterator(), false)
            .anyMatch(candidate -> candidate.asText().equals(text))) {
      errors.add(path + " is not an allowed value");
    }
  }

  private static boolean matches(String type, JsonNode value) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isTextual();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      default -> true;
    };
  }
}
