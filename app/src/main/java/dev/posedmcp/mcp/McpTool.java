package dev.posedmcp.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A single MCP tool: the descriptor an agent sees, plus the code behind it.
 */
public final class McpTool {

    /** Thrown by a handler to produce a tool error the agent can read and act on. */
    public static final class ToolError extends Exception {
        public ToolError(String message) {
            super(message);
        }
    }

    public interface Handler {
        /** @return the result payload, or {@code null} for an empty success */
        JSONObject call(JSONObject args) throws Exception;
    }

    public final String name;
    public final String title;
    public final String description;
    public final JSONObject inputSchema;
    public final boolean readOnly;
    public final Handler handler;

    private McpTool(Builder b) {
        this.name = b.name;
        this.title = b.title;
        this.description = b.description;
        this.inputSchema = b.inputSchema;
        this.readOnly = b.readOnly;
        this.handler = b.handler;
    }

    JSONObject describe() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("title", title);
            o.put("description", description);
            o.put("inputSchema", inputSchema);
            JSONObject annotations = new JSONObject();
            annotations.put("title", title);
            annotations.put("readOnlyHint", readOnly);
            annotations.put("destructiveHint", !readOnly);
            annotations.put("idempotentHint", readOnly);
            o.put("annotations", annotations);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static Builder of(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String title = "";
        private String description = "";
        private JSONObject inputSchema = object(new JSONObject());
        private boolean readOnly = true;
        private Handler handler;

        private Builder(String name) {
            this.name = name;
            this.title = name;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder input(JSONObject properties, String... required) {
            JSONObject schema = object(new JSONObject());
            JSONObject props = properties == null ? new JSONObject() : properties;
            try {
                schema.put("type", "object");
                schema.put("properties", props);
                schema.put("additionalProperties", false);
                if (required != null && required.length > 0) {
                    JSONArray req = new JSONArray();
                    for (String r : required) {
                        req.put(r);
                    }
                    schema.put("required", req);
                }
            } catch (Throwable ignored) {
            }
            this.inputSchema = schema;
            return this;
        }

        /** Marks the tool as free of side effects, which affects agent behaviour. */
        public Builder readOnly() {
            this.readOnly = true;
            return this;
        }

        /** Marks the tool as changing device state. */
        public Builder mutating() {
            this.readOnly = false;
            return this;
        }

        public Builder handler(Handler handler) {
            this.handler = handler;
            return this;
        }

        public McpTool build() {
            if (handler == null) {
                throw new IllegalStateException("tool '" + name + "' has no handler");
            }
            return new McpTool(this);
        }
    }

    // ---- schema helpers ---------------------------------------------------

    public static JSONObject object(JSONObject properties) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "object");
            o.put("properties", properties);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONObject string(String description) {
        return type("string", description);
    }

    public static JSONObject integer(String description) {
        return type("integer", description);
    }

    public static JSONObject type(String type, String description) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            o.put("description", description);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONObject array(String itemType, String description) {
        JSONObject o = type("array", description);
        try {
            o.put("items", type(itemType, ""));
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ---- result helpers ---------------------------------------------------

    /** A plain text tool result. */
    public static JSONObject text(String value) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("type", "text");
            item.put("text", value == null ? "" : value);
            content.put(item);
            result.put("content", content);
            result.put("isError", false);
        } catch (Throwable ignored) {
        }
        return result;
    }

    /**
     * A tool result that is machine-readable and also rendered for humans.
     *
     * <p>Both forms are emitted: {@code structuredContent} for agents that
     * consume it, and a JSON text block so every client shows something.
     */
    public static JSONObject json(JSONObject structured) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("type", "text");
            item.put("text", structured.toString(2));
            content.put(item);
            result.put("content", content);
            result.put("structuredContent", structured);
            result.put("isError", false);
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** An error the agent is expected to read and recover from. */
    public static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("type", "text");
            item.put("text", message == null ? "unknown error" : message);
            content.put(item);
            result.put("content", content);
            result.put("isError", true);
        } catch (Throwable ignored) {
        }
        return result;
    }
}
