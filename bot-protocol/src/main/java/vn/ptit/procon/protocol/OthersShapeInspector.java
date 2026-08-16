package vn.ptit.procon.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Produces deterministic, bounded, value-free diagnostics for opaque opponent data. */
public final class OthersShapeInspector {

    static final int MAX_DEPTH = 4;
    static final int MAX_ARRAY_SAMPLES = 3;
    static final int MAX_OBJECT_FIELDS = 16;
    static final int MAX_FIELD_NAME_LENGTH = 40;
    static final int MAX_SHAPE_LENGTH = 600;

    private OthersShapeInspector() {
    }

    public static OthersShapeSummary inspect(JsonNode others) {
        if (others == null || others.isMissingNode()) {
            return new OthersShapeSummary("ABSENT", 0, "ABSENT", false);
        }
        RenderState state = new RenderState();
        String shape = render(others, 0, state);
        if (shape.length() > MAX_SHAPE_LENGTH) {
            shape = shape.substring(0, MAX_SHAPE_LENGTH) + "...";
            state.truncated = true;
        }
        int entries = others.isContainerNode() ? others.size() : 0;
        return new OthersShapeSummary(
                others.getNodeType().name(), entries, shape, state.truncated);
    }

    private static String render(JsonNode node, int depth, RenderState state) {
        if (node == null || node.isNull()) {
            return "NULL";
        }
        if (depth >= MAX_DEPTH && node.isContainerNode()) {
            state.truncated = true;
            return node.isArray() ? "ARRAY[...]" : "OBJECT{...}";
        }
        if (node.isArray()) {
            return renderArray(node, depth, state);
        }
        if (node.isObject()) {
            return renderObject(node, depth, state);
        }
        return node.getNodeType().name();
    }

    private static String renderArray(JsonNode array, int depth, RenderState state) {
        Set<String> elementShapes = new LinkedHashSet<>();
        int sampleCount = Math.min(array.size(), MAX_ARRAY_SAMPLES);
        for (int index = 0; index < sampleCount; index++) {
            elementShapes.add(render(array.get(index), depth + 1, state));
        }
        if (array.size() > sampleCount) {
            state.truncated = true;
        }
        return "ARRAY[" + String.join("|", elementShapes) + "]";
    }

    private static String renderObject(JsonNode object, int depth, RenderState state) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> iterator = object.fields();
        while (iterator.hasNext()) {
            fields.add(iterator.next());
        }
        fields.sort(Map.Entry.comparingByKey());
        int fieldCount = Math.min(fields.size(), MAX_OBJECT_FIELDS);
        List<String> renderedFields = new ArrayList<>(fieldCount);
        for (int index = 0; index < fieldCount; index++) {
            Map.Entry<String, JsonNode> field = fields.get(index);
            renderedFields.add(sanitizeFieldName(field.getKey(), state)
                    + ":" + render(field.getValue(), depth + 1, state));
        }
        if (fields.size() > fieldCount) {
            state.truncated = true;
        }
        return "OBJECT{" + String.join(",", renderedFields) + "}";
    }

    private static String sanitizeFieldName(String fieldName, RenderState state) {
        StringBuilder sanitized = new StringBuilder();
        int limit = Math.min(fieldName.length(), MAX_FIELD_NAME_LENGTH);
        for (int index = 0; index < limit; index++) {
            char character = fieldName.charAt(index);
            if (Character.isLetterOrDigit(character)
                    || character == '_' || character == '-') {
                sanitized.append(character);
            } else {
                sanitized.append('?');
                state.truncated = true;
            }
        }
        if (fieldName.length() > limit) {
            sanitized.append("...");
            state.truncated = true;
        }
        return sanitized.toString();
    }

    private static final class RenderState {
        private boolean truncated;
    }
}
