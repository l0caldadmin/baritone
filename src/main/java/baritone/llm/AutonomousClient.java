package baritone.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class AutonomousClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    public static void executeTurn() {
        String endpoint = ConfigManager.getInstance().autonomous_endpoint;
        
        JsonObject body = new JsonObject();
        body.addProperty("model", "llama3"); // Adjust as necessary for local LLM
        
        body.add("messages", ConversationHistory.getInstance().getMessagesAsJson());
        
        JsonArray tools = new JsonArray();
        
        // mc_goto
        JsonObject gotoTool = new JsonObject();
        gotoTool.addProperty("type", "function");
        JsonObject gotoFunc = new JsonObject();
        gotoFunc.addProperty("name", "mc_goto");
        gotoFunc.addProperty("description", "Walk to coordinates");
        gotoFunc.add("parameters", GSON.fromJson("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}", JsonObject.class));
        gotoTool.add("function", gotoFunc);
        tools.add(gotoTool);

        // mc_stop
        JsonObject stopTool = new JsonObject();
        stopTool.addProperty("type", "function");
        JsonObject stopFunc = new JsonObject();
        stopFunc.addProperty("name", "mc_stop");
        stopFunc.addProperty("description", "Stops current pathing immediately");
        stopFunc.add("parameters", GSON.fromJson("{\"type\":\"object\",\"properties\":{}}", JsonObject.class));
        stopTool.add("function", stopFunc);
        tools.add(stopTool);

        // mc_status
        JsonObject statusTool = new JsonObject();
        statusTool.addProperty("type", "function");
        JsonObject statusFunc = new JsonObject();
        statusFunc.addProperty("name", "mc_status");
        statusFunc.addProperty("description", "Check if bot is currently pathing");
        statusFunc.add("parameters", GSON.fromJson("{\"type\":\"object\",\"properties\":{}}", JsonObject.class));
        statusTool.add("function", statusFunc);
        tools.add(statusTool);

        // mc_chat
        JsonObject chatTool = new JsonObject();
        chatTool.addProperty("type", "function");
        JsonObject chatFunc = new JsonObject();
        chatFunc.addProperty("name", "mc_chat");
        chatFunc.addProperty("description", "Sends a message to the player via Minecraft chat. Use this to respond to the user instead of raw text.");
        chatFunc.add("parameters", GSON.fromJson("{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}", JsonObject.class));
        chatTool.add("function", chatFunc);
        tools.add(chatTool);
        
        body.add("tools", tools);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    try {
                        JsonObject res = GSON.fromJson(response.body(), JsonObject.class);
                        if (!res.has("choices") || res.getAsJsonArray("choices").size() == 0) {
                            return;
                        }
                        
                        JsonObject message = res.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
                        String content = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
                        JsonArray toolCalls = message.has("tool_calls") ? message.getAsJsonArray("tool_calls") : null;

                        // Add assistant message to history
                        ConversationHistory.getInstance().addAssistantMessage(content, toolCalls);

                        // If no tool calls, we are done
                        if (toolCalls == null || toolCalls.size() == 0) {
                            return;
                        }

                        // Execute all tool calls
                        for (JsonElement tcElement : toolCalls) {
                            JsonObject toolCall = tcElement.getAsJsonObject();
                            String toolCallId = toolCall.get("id").getAsString();
                            JsonObject function = toolCall.getAsJsonObject("function");
                            String name = function.get("name").getAsString();
                            String argumentsStr = function.get("arguments").getAsString();

                            // Construct payload for ActionDispatcher
                            JsonObject dispatchPayload = new JsonObject();
                            dispatchPayload.addProperty("action", name);
                            dispatchPayload.add("args", GSON.fromJson(argumentsStr, JsonObject.class));

                            ActionDispatcher.dispatch(GSON.toJson(dispatchPayload)).thenAccept(dispatchRes -> {
                                ConversationHistory.getInstance().addToolMessage(toolCallId, dispatchRes);
                                // Recursively call executeTurn to let LLM generate the next step
                                executeTurn();
                            });
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
    }
}
