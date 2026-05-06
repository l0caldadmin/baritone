package baritone.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

public class AutonomousClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    public static void executeTurn() {
        ConfigManager cfg = ConfigManager.getInstance();

        // --- Build request body ---
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.add("messages", ConversationHistory.getInstance().getMessagesAsJson());

        if (!cfg.isOpenAI()) {
            // Ollama requires stream=false to return a single JSON response
            body.addProperty("stream", false);
        }

        body.add("tools", buildTools());

        // --- Build HTTP request ---
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getChatUrl()))
                .header("Content-Type", "application/json");

        if (cfg.isOpenAI() && cfg.api_key != null && !cfg.api_key.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + cfg.api_key);
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    try {
                        handleResponse(response.body(), cfg.isOpenAI());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    return null;
                });
    }

    private static void handleResponse(String responseBody, boolean isOpenAI) {
        JsonObject res = JsonParser.parseString(responseBody).getAsJsonObject();

        JsonObject message;
        if (isOpenAI) {
            // OpenAI: { "choices": [ { "message": { ... } } ] }
            if (!res.has("choices") || res.getAsJsonArray("choices").size() == 0) return;
            message = res.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        } else {
            // Ollama: { "message": { "role": "assistant", "content": "...", "tool_calls": [...] } }
            if (!res.has("message")) return;
            message = res.getAsJsonObject("message");
        }

        String content = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : "";
        JsonArray toolCalls = message.has("tool_calls") ? message.getAsJsonArray("tool_calls") : null;

        ConversationHistory.getInstance().addAssistantMessage(content, toolCalls);

        if (toolCalls == null || toolCalls.size() == 0) return;

        for (JsonElement tcElement : toolCalls) {
            JsonObject toolCall = tcElement.getAsJsonObject();
            JsonObject function = toolCall.getAsJsonObject("function");

            String name = function.get("name").getAsString();

            // OpenAI: arguments is a JSON string. Ollama: arguments is already a JsonObject.
            JsonObject args;
            JsonElement rawArgs = function.get("arguments");
            if (rawArgs.isJsonObject()) {
                args = rawArgs.getAsJsonObject(); // Ollama
            } else {
                args = JsonParser.parseString(rawArgs.getAsString()).getAsJsonObject(); // OpenAI
            }

            // Ollama doesn't supply a tool call id — generate a stable one
            String toolCallId = (toolCall.has("id") && !toolCall.get("id").isJsonNull())
                    ? toolCall.get("id").getAsString()
                    : "tc-" + UUID.randomUUID().toString().substring(0, 8);

            JsonObject dispatchPayload = new JsonObject();
            dispatchPayload.addProperty("action", name);
            dispatchPayload.add("args", args);

            ActionDispatcher.dispatch(GSON.toJson(dispatchPayload)).thenAccept(dispatchRes -> {
                ConversationHistory.getInstance().addToolMessage(toolCallId, dispatchRes);
                executeTurn(); // recursive: let LLM react to tool result
            });
        }
    }

    private static JsonArray buildTools() {
        JsonArray tools = new JsonArray();

        tools.add(makeTool("mc_goto", "Walk the bot to specific coordinates",
                "{\"type\":\"object\",\"properties\":{" +
                "\"x\":{\"type\":\"integer\",\"description\":\"X coordinate\"}," +
                "\"y\":{\"type\":\"integer\",\"description\":\"Y coordinate\"}," +
                "\"z\":{\"type\":\"integer\",\"description\":\"Z coordinate\"}" +
                "},\"required\":[\"x\",\"y\",\"z\"]}"));

        tools.add(makeTool("mc_stop", "Stop all current pathing immediately",
                "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(makeTool("mc_status", "Check if the bot is currently pathing to a goal",
                "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(makeTool("mc_chat", "Send a visible message to the player in Minecraft chat",
                "{\"type\":\"object\",\"properties\":{" +
                "\"message\":{\"type\":\"string\",\"description\":\"The message to display\"}" +
                "},\"required\":[\"message\"]}"));

        return tools;
    }

    private static JsonObject makeTool(String name, String description, String paramsJson) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);
        func.add("parameters", JsonParser.parseString(paramsJson).getAsJsonObject());
        tool.add("function", func);
        return tool;
    }
}
