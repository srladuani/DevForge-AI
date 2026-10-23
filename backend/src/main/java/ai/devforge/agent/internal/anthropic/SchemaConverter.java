package ai.devforge.agent.internal.anthropic;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Converts the small JSON-Schema subset our tools use (object, array, string, integer, number, boolean, enum) into LangChain4j's schema types. */
final class SchemaConverter {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SchemaConverter() {}

    static JsonObjectSchema objectSchema(String schemaJson) {
        JsonNode root = JSON.readTree(schemaJson == null || schemaJson.isBlank() ? "{\"type\":\"object\",\"properties\":{}}" : schemaJson);
        JsonSchemaElement e = convert(root);
        if (e instanceof JsonObjectSchema o) return o;
        throw new IllegalArgumentException("A tool's input schema must be an object");
    }

    private static JsonSchemaElement convert(JsonNode n) {
        String desc = n.path("description").asString(null);
        if (n.has("enum")) {
            List<String> values = new ArrayList<>();
            n.path("enum").forEach(v -> values.add(v.asString()));
            return JsonEnumSchema.builder().enumValues(values).description(desc).build();
        }
        return switch (n.path("type").asString("object")) {
            case "string" -> JsonStringSchema.builder().description(desc).build();
            case "integer" -> JsonIntegerSchema.builder().description(desc).build();
            case "number" -> JsonNumberSchema.builder().description(desc).build();
            case "boolean" -> JsonBooleanSchema.builder().description(desc).build();
            case "array" -> JsonArraySchema.builder().items(convert(n.path("items"))).description(desc).build();
            case "object" -> {
                JsonObjectSchema.Builder b = JsonObjectSchema.builder().description(desc);
                n.path("properties").propertyNames().forEach(name -> b.addProperty(name, convert(n.path("properties").path(name))));
                List<String> required = new ArrayList<>();
                n.path("required").forEach(r -> required.add(r.asString()));
                if (!required.isEmpty()) b.required(required);
                yield b.build();
            }
            default -> throw new IllegalArgumentException("Unsupported schema type: " + n.path("type").asString());
        };
    }
}
