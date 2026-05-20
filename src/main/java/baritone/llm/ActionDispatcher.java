package baritone.llm;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.StackedItemContents;

import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.Helper;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
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
                    
                    case "mc_mine": {
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
                    }

                    case "mc_hunt": {
                        if (!TaskRegistry.startTask("mc_hunt")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        String huntType = args.get("entity_type").getAsString();
                        int quantity = args.has("quantity") ? args.get("quantity").getAsInt() : 1;
                        
                        baritone.api.IBaritone huntBt = BaritoneAPI.getProvider().getPrimaryBaritone();
                        net.minecraft.world.entity.Entity huntTarget = huntBt.getPlayerContext().entitiesStream()
                            .filter(e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equalsIgnoreCase(huntType))
                            .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(Minecraft.getInstance().player)))
                            .orElse(null);
                        
                        if (huntTarget != null) {
                            TaskRegistry.Task huntTask = TaskRegistry.getActiveTask();
                            huntTask.lockedEntityId = huntTarget.getId();
                            huntTask.targetSource = huntType;
                            huntTask.targetQuantity = quantity;
                            huntTask.currentCount = 0;
                            
                            huntBt.getFollowProcess().follow(e -> e.getId() == huntTarget.getId());
                            response.addProperty("status", "success");
                            response.addProperty("message", "Found " + huntType + ". Hunting " + quantity + " now.");
                        } else {
                            TaskRegistry.clearTask("not_found");
                            response.addProperty("status", "error");
                            response.addProperty("message", "Could not find any " + huntType + " to hunt.");
                        }
                        break;
                    }

                    case "mc_gather_items": {
                        if (!TaskRegistry.startTask("mc_gather_items")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        int radius = args.has("radius") ? args.get("radius").getAsInt() : 32;
                        TaskRegistry.Task gatherTask = TaskRegistry.getActiveTask();
                        gatherTask.targetQuantity = radius; // reuse field for radius
                        gatherTask.lastProgressTime = System.currentTimeMillis();
                        
                        response.addProperty("status", "success");
                        response.addProperty("message", "Starting to gather items within " + radius + " blocks.");
                        break;
                    }

                    case "mc_craft": {
                        String itemId = args.get("item_id").getAsString();
                        int quantity = args.has("quantity") ? args.get("quantity").getAsInt() : 1;
                        
                        if (!TaskRegistry.startTask("mc_craft")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type);
                            break;
                        }

                        Minecraft mc = Minecraft.getInstance();
                        // Use RecipeBook for client-side recipe resolution in 1.21.1
                        RecipeDisplayEntry bestRecipe = findBestRecipe(mc.player, itemId);
                        
                        if (bestRecipe != null) {
                            // 1. Check if we need to open a table
                            boolean isTableOpen = mc.player.containerMenu instanceof CraftingMenu;
                            // Heuristic: if requirements > 4, it's 3x3
                            boolean needsTable = false;
                            java.util.Optional<java.util.List<Ingredient>> reqs = bestRecipe.craftingRequirements();
                            if (reqs.isPresent() && reqs.get().size() > 4) {
                                needsTable = true;
                            }
                            
                            if (needsTable && !isTableOpen) {
                                BlockPos tablePos = findNearbyBlock(mc, "crafting_table", 8);
                                if (tablePos != null) {
                                    AutonomousClient.onExternalTrigger("Auto-interacting with nearby crafting table at " + tablePos.getX() + "," + tablePos.getY() + "," + tablePos.getZ());
                                    interactWithBlock(mc, tablePos);
                                } else {
                                    AutonomousClient.onExternalTrigger("Recipe for " + itemId + " requires a 3x3 grid, but no crafting table was found within 8 blocks.");
                                    TaskRegistry.clearTask("error");
                                    response.addProperty("status", "error");
                                    response.addProperty("message", "Crafting table required for " + itemId);
                                    break;
                                }
                            }

                            executeCraft(bestRecipe, quantity, mc);
                            response.addProperty("status", "success");
                            response.addProperty("message", "Found recipe for " + itemId + ". Crafting...");
                        } else {
                            // 2. Fallback to CDN
                            RecipeService.getRecipe(itemId).thenAccept(recipeJson -> {
                                if (recipeJson != null) {
                                    AutonomousClient.onExternalTrigger("Resolved custom recipe for " + itemId + " via CDN.");
                                    TaskRegistry.clearTask("success");
                                } else {
                                    AutonomousClient.onExternalTrigger("Could not find any recipe for " + itemId + " in recipe book or CDN.");
                                    TaskRegistry.clearTask("error");
                                }
                            });
                            response.addProperty("status", "success");
                            response.addProperty("message", "Checking CDN for custom recipe " + itemId);
                        }
                        break;
                    }

                    case "mc_altoclef": {
                        try {
                            Class<?> bridgeClass = Class.forName("baritone.llm.AltoClefBridge");
                            java.lang.reflect.Method execute = bridgeClass.getMethod("executeTask", String.class, com.google.gson.JsonObject.class);
                            CompletableFuture<String> resFuture = (CompletableFuture<String>) execute.invoke(null, args.get("task").getAsString(), args);
                            resFuture.thenAccept(future::complete);
                            return; 
                        } catch (Exception e) {
                            JsonObject err = new JsonObject();
                            err.addProperty("status", "error");
                            err.addProperty("message", "AltoClef bridge not found in this build. Use the -altoclef version.");
                            future.complete(new Gson().toJson(err));
                            return;
                        }
                    }

                    case "mc_get_to_entity":
                        if (!TaskRegistry.startTask("mc_get_to_entity")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        String targetType = args.get("entity_type").getAsString();
                        baritone.api.IBaritone entityBt = BaritoneAPI.getProvider().getPrimaryBaritone();
                        net.minecraft.world.entity.Entity nearest = entityBt.getPlayerContext().entitiesStream()
                            .filter(e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equalsIgnoreCase(targetType))
                            .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(Minecraft.getInstance().player)))
                            .orElse(null);
                        
                        if (nearest != null) {
                            entityBt.getFollowProcess().follow(e -> e.getId() == nearest.getId());
                            response.addProperty("status", "success");
                            response.addProperty("message", "Found nearest " + targetType + " at " + nearest.blockPosition().getX() + ", " + nearest.blockPosition().getZ() + ". Pathing now.");
                        } else {
                            TaskRegistry.clearTask("not_found");
                            response.addProperty("status", "error");
                            response.addProperty("message", "Could not find any entity of type: " + targetType + " in loaded chunks.");
                        }
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

                    case "mc_build": {
                        if (!TaskRegistry.startTask("mc_build")) {
                            response.addProperty("status", "error");
                            response.addProperty("message", "Already performing " + TaskRegistry.getActiveTask().type + ". Use mc_stop first.");
                            break;
                        }
                        String rawSchematic = args.get("schematic").getAsString();
                        File schematicFile = findBestSchematic(rawSchematic);
                        int bx = args.has("x") ? args.get("x").getAsInt() : Minecraft.getInstance().player.blockPosition().getX();
                        int by = args.has("y") ? args.get("y").getAsInt() : Minecraft.getInstance().player.blockPosition().getY();
                        int bz = args.has("z") ? args.get("z").getAsInt() : Minecraft.getInstance().player.blockPosition().getZ();
                        BlockPos bpos = new BlockPos(bx, by, bz);

                        boolean started = false;
                        if (schematicFile != null) {
                            started = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().build(schematicFile.getName(), schematicFile, bpos);
                        }

                        if (started) {
                            response.addProperty("status", "success");
                            response.addProperty("message", "Started building " + schematicFile.getName() + " at " + bx + ", " + by + ", " + bz);
                        } else {
                            TaskRegistry.clearTask("failed_to_load");
                            response.addProperty("status", "error");
                            response.addProperty("message", "Failed to load schematic: " + rawSchematic + ". Make sure it is in the schematics folder.");
                        }
                        break;
                    }

                    case "mc_schematic_list": {
                        List<File> dirs = new ArrayList<>();
                        dirs.add(new File(Minecraft.getInstance().gameDirectory, "schematics"));
                        dirs.add(new File(Minecraft.getInstance().gameDirectory, "scratch/schematics"));
                        
                        JsonArray files = new JsonArray();
                        for (File schematicDir : dirs) {
                            if (schematicDir.exists() && schematicDir.isDirectory()) {
                                File[] list = schematicDir.listFiles();
                                if (list != null) {
                                    for (File f : list) {
                                        if (f.isFile() && (f.getName().endsWith(".schematic") || f.getName().endsWith(".schem") || f.getName().endsWith(".litematic"))) {
                                            if (!files.contains(new com.google.gson.JsonPrimitive(f.getName()))) {
                                                files.add(f.getName());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        response.add("schematics", files);
                        response.addProperty("status", "success");
                        response.addProperty("message", "Found " + files.size() + " schematics.");
                        break;
                    }

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
                        
                        if (active != null) {
                            response.addProperty("quantity_target", active.targetQuantity);
                            response.addProperty("quantity_current", active.currentCount);
                            
                            if (active.type.equals("mc_build")) {
                                response.addProperty("builder_active", statusBt.getBuilderProcess().isActive());
                                response.addProperty("is_paused", statusBt.getBuilderProcess().isPaused());
                            }

                            if (active.lockedTarget != null) {
                                JsonObject target = new JsonObject();
                                target.addProperty("x", active.lockedTarget.getX());
                                target.addProperty("y", active.lockedTarget.getY());
                                target.addProperty("z", active.lockedTarget.getZ());
                                response.add("target", target);
                            }
                        }
                        break;

                    case "mc_settings":
                        String settingName = args.get("setting").getAsString();
                        baritone.api.Settings settings = BaritoneAPI.getSettings();
                        try {
                            java.lang.reflect.Field field = settings.getClass().getField(settingName);
                            baritone.api.Settings.Setting<?> setting = (baritone.api.Settings.Setting<?>) field.get(settings);
                            
                            if (args.has("value")) {
                                if (setting.value instanceof Boolean) {
                                    @SuppressWarnings("unchecked")
                                    baritone.api.Settings.Setting<Boolean> s = (baritone.api.Settings.Setting<Boolean>) setting;
                                    s.value = args.get("value").getAsBoolean();
                                } else if (setting.value instanceof Double) {
                                    @SuppressWarnings("unchecked")
                                    baritone.api.Settings.Setting<Double> s = (baritone.api.Settings.Setting<Double>) setting;
                                    s.value = args.get("value").getAsDouble();
                                } else if (setting.value instanceof Integer) {
                                    @SuppressWarnings("unchecked")
                                    baritone.api.Settings.Setting<Integer> s = (baritone.api.Settings.Setting<Integer>) setting;
                                    s.value = args.get("value").getAsInt();
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
                        JsonArray waypoints = new JsonArray();
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

    private static RecipeDisplayEntry findBestRecipe(net.minecraft.client.player.LocalPlayer player, String itemId) {
        String cleanId = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        String singular = cleanId.endsWith("s") ? cleanId.substring(0, cleanId.length() - 1) : cleanId;
        String plural = cleanId.endsWith("s") ? cleanId : cleanId + "s";
        
        java.util.List<String> variations = new java.util.ArrayList<>();
        variations.add(cleanId);
        variations.add(singular);
        variations.add(plural);
        variations.add(cleanId.replace("plank", "planks"));
        variations.add(cleanId.replace("log", "logs"));
        
        java.util.List<RecipeDisplayEntry> candidates = new java.util.ArrayList<>();
        
        for (net.minecraft.client.gui.screens.recipebook.RecipeCollection collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                // In 1.21.1, we can get the result stack from the display
                ItemStack result = entry.display().result().resolveForFirstStack(null);
                if (result == null || result.isEmpty()) continue;
                
                String resultId = BuiltInRegistries.ITEM.getKey(result.getItem()).getPath();
                for (String var : variations) {
                    if (resultId.equalsIgnoreCase(var)) {
                        candidates.add(entry);
                        break;
                    }
                }
            }
        }
        
        if (candidates.isEmpty()) return null;
        
        // Ingredient-aware selection: pick the first one we can actually craft
        net.minecraft.world.entity.player.StackedItemContents stacked = new net.minecraft.world.entity.player.StackedItemContents();
        player.getInventory().fillStackedContents(stacked);
        
        for (RecipeDisplayEntry entry : candidates) {
            if (entry.canCraft(stacked)) {
                return entry;
            }
        }
        
        return candidates.get(0);
    }

    private static BlockPos findNearbyBlock(Minecraft mc, String blockId, int radius) {
        BlockPos playerPos = mc.player.blockPosition();
        baritone.api.utils.IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
        baritone.utils.BlockStateInterface bsi = new baritone.utils.BlockStateInterface(ctx);
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    Block block = bsi.get0(pos).getBlock();
                    if (BuiltInRegistries.BLOCK.getKey(block).getPath().contains(blockId)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static void interactWithBlock(Minecraft mc, BlockPos pos) {
        Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        baritone.api.utils.IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
        Rotation rot = RotationUtils.calcRotationFromCoords(ctx.player().blockPosition(), pos);
        BaritoneAPI.getProvider().getPrimaryBaritone().getLookBehavior().updateTarget(rot, true);
        
        mc.gameMode.useItemOn(
            mc.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(blockCenter, Direction.UP, pos, false)
        );
    }

    private static void executeCraft(RecipeDisplayEntry entry, int quantity, Minecraft mc) {
        // 1. Send Place Recipe Packet
        // quantity > 1 tells the server to use shift-click behavior (populate as much as possible)
        boolean shiftDown = quantity > 1;
        int containerId = mc.player.containerMenu.containerId;
        
        mc.getConnection().send(new ServerboundPlaceRecipePacket(
            containerId, 
            entry.id(), 
            shiftDown
        ));

        // 2. Click the result slot (Slot 0) to "grab" the items
        // We use QUICK_MOVE (Shift-Click) to automatically move items into the inventory
        mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
            containerId,
            mc.player.containerMenu.getStateId(),
            (short)0, // Result slot
            (byte)0, // Button 0
            net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, 
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),
            net.minecraft.network.HashedStack.EMPTY
        ));

        AutonomousClient.onExternalTrigger("Sent crafting and pickup packets for " + entry.id().index());
        TaskRegistry.clearTask("success");
    }

    private static File findBestSchematic(String query) {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(Minecraft.getInstance().gameDirectory, "schematics"));
        dirs.add(new File(Minecraft.getInstance().gameDirectory, "scratch/schematics")); // Support user's scratch dir

        File bestMatch = null;
        double bestScore = 0;

        String normalizedQuery = query.toLowerCase().replace("_", " ").replace("-", " ").trim();
        if (normalizedQuery.endsWith(".schematic") || normalizedQuery.endsWith(".schem") || normalizedQuery.endsWith(".litematic")) {
            normalizedQuery = normalizedQuery.substring(0, normalizedQuery.lastIndexOf('.'));
        }

        for (File schematicDir : dirs) {
            if (!schematicDir.exists() || !schematicDir.isDirectory()) continue;

            File[] files = schematicDir.listFiles();
            if (files == null) continue;

            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase();
                String nameNoExt = name;
                if (name.contains(".")) {
                    nameNoExt = name.substring(0, name.lastIndexOf('.'));
                }

                // Exact match
                if (f.getName().equalsIgnoreCase(query)) return f;
                if (nameNoExt.equalsIgnoreCase(normalizedQuery)) return f;

                // Fuzzy match: check if query is a substring or vice-versa
                String normalizedName = nameNoExt.replace("_", " ").replace("-", " ");
                if (normalizedName.contains(normalizedQuery) || normalizedQuery.contains(normalizedName)) {
                    double score = (double) Math.min(normalizedName.length(), normalizedQuery.length()) / Math.max(normalizedName.length(), normalizedQuery.length());
                    if (score > bestScore) {
                        bestScore = score;
                        bestMatch = f;
                    }
                }
            }
        }

        return bestMatch;
    }
}
