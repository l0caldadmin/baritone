package baritone.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedList;
import java.util.List;

public class ConversationHistory {
    
    private static final int MAX_HISTORY = 30;
    private static final ConversationHistory INSTANCE = new ConversationHistory();
    
    private final List<JsonObject> messages = new LinkedList<>();

    public static ConversationHistory getInstance() {
        return INSTANCE;
    }

    public synchronized void addUserMessage(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", content);
        addMessage(msg);
    }

    public synchronized void addAssistantMessage(String content, JsonArray toolCalls) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        if (content != null && !content.isEmpty()) {
            msg.addProperty("content", content);
        } else {
            // Some endpoints require content to be present or null
            msg.addProperty("content", "");
        }
        if (toolCalls != null && toolCalls.size() > 0) {
            msg.add("tool_calls", toolCalls);
        }
        addMessage(msg);
    }

    public synchronized void addToolMessage(String toolCallId, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "tool");
        msg.addProperty("tool_call_id", toolCallId);
        msg.addProperty("content", content);
        addMessage(msg);
    }

    private synchronized void addMessage(JsonObject msg) {
        messages.add(msg);
        if (messages.size() > MAX_HISTORY) {
            messages.remove(0); // Very naive rolling window
        }
    }

    public synchronized JsonArray getMessagesAsJson() {
        JsonArray arr = new JsonArray();
        for (JsonObject msg : messages) {
            arr.add(msg);
        }
        return arr;
    }

    public synchronized void clear() {
        messages.clear();
    }
}
