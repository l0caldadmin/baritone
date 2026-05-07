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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AutonomousClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static boolean isProcessing = false;
    private static boolean needsAnotherTurn = false;

    static {
        // Enforce silent settings to prevent Baritone chat spam
        baritone.api.BaritoneAPI.getSettings().chatDebug.value = false;
        baritone.api.BaritoneAPI.getSettings().echoCommands.value = false;
    }

    public static synchronized void onChatMessage(String sender, String message) {
        // Filter out bot messages and system noise
        if (sender.equals("System") || sender.equalsIgnoreCase("Baritone")) return;
        
        // Don't respond to ourselves if we can detect it
        // (This depends on the sender string being accurate)
        
        AutonomousLogger.log("CHAT", sender + ": " + message);
        ConversationHistory.getInstance().addUserMessage(sender + ": " + message);
        executeTurn();
    }

    public static synchronized void executeTurn() {
        if (isProcessing) {
            needsAnotherTurn = true;
            return;
        }
        isProcessing = true;
        needsAnotherTurn = false;
        
        ConfigManager cfg = ConfigManager.getInstance();

        // --- Build request body ---
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        
        JsonArray messages = new JsonArray();
        // Inject System Prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "You are an autonomous AI agent controlling a Minecraft bot via Baritone. " +
            "Your goal is to fulfill user requests by planning and executing multiple steps. " +
            "STRATEGY: You must think step-by-step. Before gathering a resource, check your inventory (`mc_inventory`) to see if you have the necessary tools.\n" +
            "TASK COMMITMENT: Once you start a long-running task (mc_goto, mc_mine, mc_follow, mc_explore, mc_get_to_block, mc_find_village), YOU MUST STOP and wait for an 'Event notification'. Do NOT call any more tools until the system notifies you of success, failure, or cancellation. Trust your path and commit to the journey.\n" +
            "COMMUNICATION: Use `mc_chat` to inform the user of your plan before executing long-running tasks.\n" +
            "RESILIENCE: If a task is canceled or fails, analyze the event notification, check your surroundings, and decide if you should retry, try a different path, or ask the user for help.");
        messages.add(systemMsg);
        
        // Add existing history
        messages.addAll(ConversationHistory.getInstance().getMessagesAsJson());
        
        body.add("messages", messages);

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

        String requestJson = GSON.toJson(body);
        AutonomousLogger.logRequest(requestJson);

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    AutonomousLogger.logResponse(response.body());
                    return handleResponse(response.body(), cfg.isOpenAI());
                })
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        ex.printStackTrace();
                        AutonomousLogger.log("ERROR", "Turn failed: " + ex.getMessage());
                    }
                    synchronized (AutonomousClient.class) {
                        isProcessing = false;
                        if (needsAnotherTurn) {
                            executeTurn();
                        }
                    }
                });
    }

    private static CompletableFuture<Void> handleResponse(String responseBody, boolean isOpenAI) {
        JsonObject res = JsonParser.parseString(responseBody).getAsJsonObject();

        JsonObject message;
        if (isOpenAI) {
            // OpenAI: { "choices": [ { "message": { ... } } ] }
            if (!res.has("choices") || res.getAsJsonArray("choices").size() == 0) {
                return CompletableFuture.completedFuture(null);
            }
            message = res.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        } else {
            // Ollama: { "message": { "role": "assistant", "content": "...", "tool_calls": [...] } }
            if (!res.has("message")) {
                return CompletableFuture.completedFuture(null);
            }
            message = res.getAsJsonObject("message");
        }

        String content = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : "";
        JsonArray toolCalls = message.has("tool_calls") ? message.getAsJsonArray("tool_calls") : null;

        ConversationHistory.getInstance().addAssistantMessage(content, toolCalls);

        if (toolCalls == null || toolCalls.size() == 0) return CompletableFuture.completedFuture(null);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        final boolean[] hasLongRunning = {false};

        for (JsonElement tcElement : toolCalls) {
            JsonObject toolCall = tcElement.getAsJsonObject();
            JsonObject function = toolCall.getAsJsonObject("function");
            String name = function.get("name").getAsString();

            JsonObject args;
            JsonElement rawArgs = function.get("arguments");
            if (rawArgs.isJsonObject()) {
                args = rawArgs.getAsJsonObject();
            } else {
                args = JsonParser.parseString(rawArgs.getAsString()).getAsJsonObject();
            }

            String toolCallId = (toolCall.has("id") && !toolCall.get("id").isJsonNull())
                    ? toolCall.get("id").getAsString()
                    : "tc-" + UUID.randomUUID().toString().substring(0, 8);

            if (name.equals("mc_goto") || name.equals("mc_mine") || name.equals("mc_follow") || 
                name.equals("mc_explore") || name.equals("mc_get_to_block") || name.equals("mc_find_village")) {
                hasLongRunning[0] = true;
            }

            futures.add(ActionDispatcher.dispatch(name, args).thenAccept(result -> {
                ConversationHistory.getInstance().addToolMessage(toolCallId, result);
            }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    synchronized (AutonomousClient.class) {
                        // Only trigger another turn immediately if we DIDN'T start a long-running task.
                        // If we did, we must wait for an external trigger (AT_GOAL, CALC_FAILED, etc.)
                        if (!hasLongRunning[0]) {
                            needsAnotherTurn = true;
                        } else {
                            baritone.api.utils.Helper.HELPER.logDirect("[Bot] Committing to task... (Turn paused)");
                        }
                    }
                });
    }

    private static boolean waitingForEvent = false;

    public static synchronized void onExternalTrigger(String eventDescription) {
        AutonomousLogger.log("EVENT", eventDescription);
        ConversationHistory.getInstance().addSystemMessage("Event notification: " + eventDescription);
        executeTurn();
    }

    private static JsonArray buildTools() {
        JsonArray tools = new JsonArray();

        tools.add(makeTool("mc_goto", "Walk the bot to specific coordinates. Returns success immediately if the path is found. You must then wait for an event notification.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"x\":{\"type\":\"integer\"}," +
                "\"y\":{\"type\":\"integer\"}," +
                "\"z\":{\"type\":\"integer\"}" +
                "},\"required\":[\"x\",\"y\",\"z\"]}"));

        tools.add(makeTool("mc_stop", "Stop all current pathing immediately",
                "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(makeTool("mc_status", "Check if the bot is currently pathing to a goal",
                "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(makeTool("mc_chat", "Send a message to the player in chat",
                "{\"type\":\"object\",\"properties\":{" +
                "\"message\":{\"type\":\"string\"}" +
                "},\"required\":[\"message\"]}"));

        tools.add(makeTool("mc_mine", "Mine blocks of a specific type. Returns success when mining starts. Wait for completion event.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"block_id\":{\"type\":\"string\"}," +
                "\"quantity\":{\"type\":\"integer\"}" +
                "},\"required\":[\"block_id\"]}"));

        tools.add(makeTool("mc_follow", "Follow a specific entity. Returns success when follow starts. Wait for event.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"entity_type\":{\"type\":\"string\"}" +
                "},\"required\":[\"entity_type\"]}"));

        tools.add(makeTool("mc_explore", "Start exploring. Returns success when explore starts. Wait for event.",
                "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(makeTool("mc_get_to_block", "Go to nearest block of type. Returns success when pathing starts. Wait for event.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"block_id\":{\"type\":\"string\"}" +
                "},\"required\":[\"block_id\"]}"));

        tools.add(makeTool("mc_scan", "Scan area for entities and blocks.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"radius\":{\"type\":\"integer\"}" +
                "}}"));

        tools.add(makeTool("mc_inventory", "List inventory items.",
                "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(makeTool("mc_interact", "Interact with block at coordinates.",
                "{\"type\":\"object\",\"properties\":{" +
                "\"x\":{\"type\":\"integer\"}," +
                "\"y\":{\"type\":\"integer\"}," +
                "\"z\":{\"type\":\"integer\"}" +
                "},\"required\":[\"x\",\"y\",\"z\"]}"));

        tools.add(makeTool("mc_find_village", "Specialized search for a village. Returns success if a village marker is found. Wait for event.",
                "{\"type\":\"object\",\"properties\":{}}"));

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
