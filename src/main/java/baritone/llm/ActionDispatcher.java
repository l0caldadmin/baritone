package baritone.llm;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import baritone.api.BaritoneAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.Helper;
import java.util.concurrent.CompletableFuture;

public class ActionDispatcher {

    private static final Gson GSON = new Gson();

    /**
     * Dispatches an action with pre-parsed arguments.
     */
    public static CompletableFuture<String> dispatch(String action, JsonObject args) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", action);
        payload.add("args", args);
        return dispatch(GSON.toJson(payload));
    }

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

                AutonomousLogger.logAction(action, args.toString());

                JsonObject response = new JsonObject();
                response.addProperty("action", action);

                switch (action) {
                    case "mc_find_village":
                        if (!TaskRegistry.startTask("mc_find_village")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        
                        TaskRegistry.Task villageTask = TaskRegistry.getActiveTask();
                        baritone.api.IBaritone bt = BaritoneAPI.getProvider().getPrimaryBaritone();
                        baritone.api.utils.IPlayerContext villageCtx = bt.getPlayerContext();
                        BlockPos startPos = villageCtx.player().blockPosition();
                        
                        // Initial immediate scan (using simplified logic, Tick loop will do thorough prioritized scan)
                        baritone.utils.BlockStateInterface vBsi = new baritone.utils.BlockStateInterface(villageCtx);
                        BlockPos initialFound = null;
                        
                        int vRadius = 64; 
                        outer:
                        for (int dx = -vRadius; dx <= vRadius; dx += 4) {
                            for (int dy = -16; dy <= 16; dy += 4) {
                                for (int dz = -vRadius; dz <= vRadius; dz += 4) {
                                    BlockPos target = startPos.offset(dx, dy, dz);
                                    Block block = vBsi.get0(target).getBlock();
                                    String bName = BuiltInRegistries.BLOCK.getKey(block).getPath();
                                    if (bName.equals("bell") || bName.contains("lectern") || bName.contains("job_site") || bName.contains("bed")) {
                                        initialFound = target;
                                        break outer;
                                    }
                                }
                            }
                        }
                        
                        if (initialFound != null) {
                            villageTask.lockedTarget = initialFound;
                            villageTask.phase = TaskRegistry.Phase.MOVING_TO_CANDIDATE;
                            villageTask.targetSource = "initial_scan";
                            villageTask.lastProgressAt = System.currentTimeMillis();
                            AutonomousLogger.log("VILLAGE_TARGET_LOCKED", "pos=" + initialFound.getX() + "," + initialFound.getY() + "," + initialFound.getZ() + " source=initial_scan");
                            bt.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(initialFound));
                            response.addProperty("status", "success");
                            response.addProperty("message", "Village found at " + initialFound.getX() + ", " + initialFound.getZ() + ". Pathing now.");
                        } else {
                            // If no markers found, start exploration. Tick loop will catch indicators later.
                            bt.getExploreProcess().explore(startPos.getX(), startPos.getZ());
                            response.addProperty("status", "success");
                            response.addProperty("message", "Starting exploration to find a village. I will lock onto the first indicator I see.");
                        }
                        break;

                    case "mc_goto":
                        if (!TaskRegistry.startTask("mc_goto")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        int x = args.get("x").getAsInt();
                        int y = args.get("y").getAsInt();
                        int z = args.get("z").getAsInt();
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
                        response.addProperty("status", "success");
                        response.addProperty("message", "Started pathing to " + x + ", " + y + ", " + z);
                        break;
                    
                    case "mc_mine":
                        if (!TaskRegistry.startTask("mc_mine")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        String blockName = args.get("block_id").getAsString();
                        int quantity = args.has("quantity") ? args.get("quantity").getAsInt() : 0;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().mineByName(quantity, blockName);
                        response.addProperty("status", "success");
                        response.addProperty("message", "Started mining " + blockName);
                        break;

                    case "mc_follow":
                        if (!TaskRegistry.startTask("mc_follow")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        String entityType = args.get("entity_type").getAsString();
                        BaritoneAPI.getProvider().getPrimaryBaritone().getFollowProcess().follow(entity -> {
                            String name = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
                            return name.equalsIgnoreCase(entityType);
                        });
                        response.addProperty("status", "success");
                        response.addProperty("message", "Started following " + entityType);
                        break;

                    case "mc_explore":
                        if (!TaskRegistry.startTask("mc_explore")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        BaritoneAPI.getProvider().getPrimaryBaritone().getExploreProcess().explore(
                            (int)Minecraft.getInstance().player.getX(), 
                            (int)Minecraft.getInstance().player.getZ()
                        );
                        response.addProperty("status", "success");
                        response.addProperty("message", "Started exploring.");
                        break;

                    case "mc_get_to_block":
                        if (!TaskRegistry.startTask("mc_get_to_block")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        String targetBlock = args.get("block_id").getAsString();
                        net.minecraft.resources.Identifier loc = net.minecraft.resources.Identifier.parse(targetBlock);
                        java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.level.block.Block>> holder = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(loc);
                        if (holder.isPresent()) {
                            BaritoneAPI.getProvider().getPrimaryBaritone().getGetToBlockProcess().getToBlock(holder.get().value());
                            response.addProperty("status", "success");
                            response.addProperty("message", "Going to nearest " + targetBlock);
                        } else {
                            TaskRegistry.clearTask("unknown_block");
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
                                ent.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
                                ent.addProperty("x", e.getX());
                                ent.addProperty("y", e.getY());
                                ent.addProperty("z", e.getZ());
                                ent.addProperty("id", e.getId());
                                entities.add(ent);
                            });
                        response.add("entities", entities);

                        // Scan for interesting blocks
                        com.google.gson.JsonArray blocks = new com.google.gson.JsonArray();
                        baritone.api.utils.IPlayerContext pctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
                        baritone.utils.BlockStateInterface bsi = new baritone.utils.BlockStateInterface(pctx);
                        net.minecraft.core.BlockPos playerPos = pctx.player().blockPosition();
                        
                        for (int dx = -radius; dx <= radius; dx++) {
                            for (int dy = -radius/2; dy <= radius/2; dy++) {
                                for (int dz = -radius; dz <= radius; dz++) {
                                    net.minecraft.core.BlockPos target = playerPos.offset(dx, dy, dz);
                                    net.minecraft.world.level.block.state.BlockState state = bsi.get0(target);
                                    if (state.isAir()) continue;
                                    
                                    String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                                    if (name.contains("ore") || name.contains("log") || name.contains("table") || 
                                        name.contains("chest") || name.contains("furnace") || name.equals("bell") || 
                                        name.contains("bed") || name.contains("job_site")) {
                                        JsonObject b = new JsonObject();
                                        b.addProperty("type", name);
                                        b.addProperty("x", target.getX());
                                        b.addProperty("y", target.getY());
                                        b.addProperty("z", target.getZ());
                                        blocks.add(b);
                                        if (blocks.size() > 50) break; // Limit to 50 interesting blocks
                                    }
                                }
                                if (blocks.size() > 50) break;
                            }
                            if (blocks.size() > 50) break;
                        }
                        response.add("blocks", blocks);
                        response.addProperty("status", "success");
                        break;

                    case "mc_interact":
                        // Right click the block at the specified coordinates
                        int ix = args.get("x").getAsInt();
                        int iy = args.get("y").getAsInt();
                        int iz = args.get("z").getAsInt();
                        BlockPos pos = new BlockPos(ix, iy, iz);
                        Vec3 blockCenter = new Vec3(ix + 0.5, iy + 0.5, iz + 0.5);
                        
                        // Look at the block first
                        baritone.api.utils.IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
                        Rotation rot = RotationUtils.calcRotationFromCoords(ctx.player().blockPosition(), pos);
                        BaritoneAPI.getProvider().getPrimaryBaritone().getLookBehavior().updateTarget(rot, true);
                        
                        // Right click
                        Minecraft.getInstance().gameMode.useItemOn(
                            Minecraft.getInstance().player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(blockCenter, Direction.UP, pos, false)
                        );
                        
                        response.addProperty("status", "success");
                        response.addProperty("message", "Interacted with block at " + ix + ", " + iy + ", " + iz);
                        break;

                    case "mc_inventory":
                        com.google.gson.JsonArray inventory = new com.google.gson.JsonArray();
                        Minecraft.getInstance().player.getInventory().getNonEquipmentItems().forEach(stack -> {
                            if (!stack.isEmpty()) {
                                JsonObject item = new JsonObject();
                                item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
                                item.addProperty("count", stack.getCount());
                                inventory.add(item);
                            }
                        });
                        response.add("inventory", inventory);
                        response.addProperty("status", "success");
                        break;

                    case "mc_stop":
                        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
                        TaskRegistry.clearTask("manual_stop");
                        response.addProperty("status", "success");
                        response.addProperty("message", "Stopped all processes and cleared task registry.");
                        break;
                    
                    case "mc_status":
                        baritone.api.IBaritone statusBt = BaritoneAPI.getProvider().getPrimaryBaritone();
                        boolean isPathing = statusBt.getPathingBehavior().isPathing();
                        TaskRegistry.Task active = TaskRegistry.getActiveTask();
                        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
                        
                        response.addProperty("status", "success");
                        response.addProperty("is_pathing", isPathing);
                        response.addProperty("active_task", active != null ? active.type : "none");
                        response.addProperty("task_phase", active != null ? active.phase.name() : "IDLE");
                        
                        if (player != null) {
                            response.addProperty("x", player.getX());
                            response.addProperty("y", player.getY());
                            response.addProperty("z", player.getZ());
                            response.addProperty("hp", player.getHealth());
                            response.addProperty("food", player.getFoodData().getFoodLevel());
                            String dim = player.level().dimension().toString();
                            if (dim.contains("/")) {
                                dim = dim.substring(dim.lastIndexOf('/') + 1, dim.length() - 1).trim();
                            }
                            response.addProperty("dimension", dim);
                        }
                        
                        if (active != null && active.lockedTarget != null) {
                            JsonObject target = new JsonObject();
                            target.addProperty("x", active.lockedTarget.getX());
                            target.addProperty("y", active.lockedTarget.getY());
                            target.addProperty("z", active.lockedTarget.getZ());
                            response.add("target", target);
                        }
                        break;

                    case "mc_settings":
                        String settingName = args.get("setting").getAsString();
                        baritone.api.Settings settings = baritone.api.BaritoneAPI.getSettings();
                        try {
                            java.lang.reflect.Field field = settings.getClass().getField(settingName);
                            baritone.api.Settings.Setting<?> setting = (baritone.api.Settings.Setting<?>) field.get(settings);
                            
                            if (args.has("value")) {
                                if (setting.value instanceof Boolean) {
                                    ((baritone.api.Settings.Setting<Boolean>)setting).value = args.get("value").getAsBoolean();
                                } else if (setting.value instanceof Double) {
                                    ((baritone.api.Settings.Setting<Double>)setting).value = args.get("value").getAsDouble();
                                } else if (setting.value instanceof Integer) {
                                    ((baritone.api.Settings.Setting<Integer>)setting).value = args.get("value").getAsInt();
                                }
                                response.addProperty("message", "Set " + settingName + " to " + setting.value);
                            }
                            response.addProperty("current_value", setting.value.toString());
                            response.addProperty("status", "success");
                        } catch (Exception e) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Setting not found: " + settingName);
                        }
                        break;

                    case "mc_waypoints":
                        com.google.gson.JsonArray waypoints = new com.google.gson.JsonArray();
                        BaritoneAPI.getProvider().getPrimaryBaritone().getWorldProvider().ifWorldLoaded(world -> {
                            world.getWaypoints().getAllWaypoints().forEach(wp -> {
                                JsonObject w = new JsonObject();
                                w.addProperty("name", wp.getName());
                                w.addProperty("tag", wp.getTag().getName());
                                w.addProperty("x", wp.getLocation().getX());
                                w.addProperty("y", wp.getLocation().getY());
                                w.addProperty("z", wp.getLocation().getZ());
                                waypoints.add(w);
                            });
                        });
                        response.add("waypoints", waypoints);
                        response.addProperty("status", "success");
                        break;

                    case "mc_chat":
                        String message = args.has("message") ? args.get("message").getAsString() : "";
                        Helper.HELPER.logDirect("[Bot] " + message);
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
