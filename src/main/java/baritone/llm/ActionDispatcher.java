package baritone.llm;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

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
                    
                    case "mc_mine":
                        String blockName = args.get("block_id").getAsString();
                        int quantity = args.has("quantity") ? args.get("quantity").getAsInt() : 0;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().mineByName(quantity, blockName);
                        response.addProperty("status", "success");
                        response.addProperty("message", "Started mining " + blockName);
                        break;

                    case "mc_follow":
                        String entityType = args.get("entity_type").getAsString();
                        BaritoneAPI.getProvider().getPrimaryBaritone().getFollowProcess().follow(entity -> {
                            String name = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
                            return name.equalsIgnoreCase(entityType);
                        });
                        response.addProperty("status", "success");
                        response.addProperty("message", "Started following " + entityType);
                        break;

                    case "mc_explore":
                        BaritoneAPI.getProvider().getPrimaryBaritone().getExploreProcess().explore(
                            (int)Minecraft.getInstance().player.getX(), 
                            (int)Minecraft.getInstance().player.getZ()
                        );
                        response.addProperty("status", "success");
                        response.addProperty("message", "Started exploring.");
                        break;

                    case "mc_get_to_block":
                        String targetBlock = args.get("block_id").getAsString();
                        net.minecraft.resources.Identifier loc = net.minecraft.resources.Identifier.parse(targetBlock);
                        java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.level.block.Block>> holder = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(loc);
                        if (holder.isPresent()) {
                            BaritoneAPI.getProvider().getPrimaryBaritone().getGetToBlockProcess().getToBlock(holder.get().value());
                            response.addProperty("status", "success");
                            response.addProperty("message", "Going to nearest " + targetBlock);
                        } else {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Unknown block: " + targetBlock);
                        }
                        break;

                    case "mc_scan":
                        int radius = args.has("radius") ? args.get("radius").getAsInt() : 32;
                        com.google.gson.JsonArray entities = new com.google.gson.JsonArray();
                        BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().entitiesStream()
                            .filter(e -> e.distanceTo(Minecraft.getInstance().player) <= radius)
                            .forEach(e -> {
                                JsonObject ent = new JsonObject();
                                ent.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
                                ent.addProperty("x", e.getX());
                                ent.addProperty("y", e.getY());
                                ent.addProperty("z", e.getZ());
                                ent.addProperty("id", e.getId());
                                entities.add(ent);
                            });
                        response.add("entities", entities);
                        response.addProperty("status", "success");
                        break;

                    case "mc_stop":
                        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
                        response.addProperty("status", "success");
                        response.addProperty("message", "Stopped all processes.");
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
