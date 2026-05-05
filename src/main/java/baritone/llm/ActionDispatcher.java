package baritone.llm;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;

public class ActionDispatcher {

    private static final Gson GSON = new Gson();

    /**
     * Dispatches a raw JSON tool call payload.
     * Returns a JSON string response.
     */
    public static CompletableFuture<String> dispatch(String rawJson) {
        CompletableFuture<String> future = new CompletableFuture<>();

        // Execute on main thread
        Minecraft.getInstance().execute(() -> {
            try {
                JsonObject payload = JsonParser.parseString(rawJson).getAsJsonObject();
                String action = payload.has("action") ? payload.get("action").getAsString() : "unknown";
                JsonObject args = payload.has("args") ? payload.getAsJsonObject("args") : new JsonObject();

                JsonObject response = new JsonObject();
                response.addProperty("action", action);

                switch (action) {
                    case "mc_goto":
                        int x = args.get("x").getAsInt();
                        int y = args.get("y").getAsInt();
                        int z = args.get("z").getAsInt();
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
                        response.addProperty("status", "success");
                        response.addProperty("message", "Started pathing to " + x + ", " + y + ", " + z);
                        break;
                    
                    case "mc_stop":
                        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
                        response.addProperty("status", "success");
                        response.addProperty("message", "Stopped pathing.");
                        break;
                    
                    case "mc_status":
                        boolean isPathing = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
                        response.addProperty("status", "success");
                        response.addProperty("is_pathing", isPathing);
                        break;

                    case "mc_chat":
                        String message = args.has("message") ? args.get("message").getAsString() : "";
                        baritone.api.utils.Helper.HELPER.logDirect("[Bot] " + message);
                        response.addProperty("status", "success");
                        response.addProperty("message", "Message sent to player chat.");
                        break;

                    default:
                        response.addProperty("status", "error");
                        response.addProperty("message", "Unknown action: " + action);
                        break;
                }

                future.complete(GSON.toJson(response));
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty("status", "error");
                error.addProperty("message", e.getMessage());
                future.complete(GSON.toJson(error));
            }
        });

        return future;
    }
}
