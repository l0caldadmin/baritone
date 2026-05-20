package baritone.llm;

import baritone.api.IBaritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.ChatEvent;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.core.registries.BuiltInRegistries;

public class AutonomousControl implements AbstractGameEventListener {

    private static boolean registered = false;
    private final IBaritone baritone;
    private PathEvent lastEvent = null;

    public AutonomousControl(IBaritone baritone) {
        this.baritone = baritone;
    }

    public static void register(IBaritone baritone) {
        if (registered) return;
        baritone.getGameEventHandler().registerEventListener(new AutonomousControl(baritone));
        registered = true;
    }

    @Override
    public void onSendChatMessage(ChatEvent event) {
        String msg = event.getMessage();
        // Ignore Baritone commands and Minecraft commands
        if (msg.startsWith("#") || msg.startsWith("/")) return;
        
        // Trigger the LLM with the player's message
        AutonomousClient.onChatMessage("Player", msg);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || event.getState() != EventState.PRE) return;

        baritone.api.utils.IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx == null || ctx.player() == null || ctx.world() == null) return;

        // --- Village Search State Machine ---
        handleVillageSearch(ctx);

        // --- Active Hunting ---
        handleHunting(ctx);

        // --- Active Gathering ---
        handleGathering(ctx);

        // --- Active Building ---
        handleBuilding(ctx);

        // --- AltoClef Tasks ---
        try {
            Class<?> bridgeClass = Class.forName("baritone.llm.AltoClefBridge");
            java.lang.reflect.Method check = bridgeClass.getMethod("checkProgress");
            check.invoke(null);
        } catch (Exception ignored) {}

        // --- Basic Auto-Defense ---
        // Scan for hostile monsters within reach and attack them
        for (Entity entity : ctx.entities()) {
            if (entity instanceof Monster && entity.isAlive()) {
                double distSq = ctx.player().distanceToSqr(entity);
                // Reach is typically around 3-4.5 blocks. Using 4.2 for safety.
                if (distSq < 4.2 * 4.2) {
                    // Attack the monster
                    ctx.minecraft().gameMode.attack(ctx.player(), entity);
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                    // Attack only one per tick
                    break;
                }
            }
        }
    }

    private void handleGathering(baritone.api.utils.IPlayerContext ctx) {
        TaskRegistry.Task task = TaskRegistry.getActiveTask();
        if (task == null || !task.type.equals("mc_gather_items")) return;

        int radius = task.targetQuantity;
        net.minecraft.world.entity.item.ItemEntity nearest = ctx.entitiesStream()
            .filter(e -> e instanceof net.minecraft.world.entity.item.ItemEntity && e.distanceToSqr(ctx.player()) < radius * radius)
            .map(e -> (net.minecraft.world.entity.item.ItemEntity)e)
            .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player())))
            .orElse(null);

        if (nearest != null) {
            task.lastProgressTime = System.currentTimeMillis();
            // Update path every 5 ticks for accuracy (items move!)
            if (ctx.player().tickCount % 5 == 0) {
                // Target the exact block the item is in
                baritone.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(nearest.blockPosition()));
            }
        } else if (System.currentTimeMillis() - task.lastProgressTime > 3000) {
            // No items for 3 seconds, assume done
            AutonomousLogger.log("GATHER_COMPLETE", "No more items found in radius.");
            baritone.getPathingBehavior().cancelEverything();
            TaskRegistry.clearTask("success");
            AutonomousClient.onExternalTrigger("Finished gathering items.");
        }
    }

    private void handleBuilding(baritone.api.utils.IPlayerContext ctx) {
        TaskRegistry.Task task = TaskRegistry.getActiveTask();
        if (task == null || !task.type.equals("mc_build")) return;

        baritone.api.process.IBuilderProcess builder = baritone.getBuilderProcess();

        if (!builder.isActive()) {
            AutonomousLogger.log("BUILD_COMPLETE", "Building process is no longer active.");
            TaskRegistry.clearTask("success");
            AutonomousClient.onExternalTrigger("Finished building structure.");
            return;
        }

        if (builder.isPaused()) {
            task.phase = TaskRegistry.Phase.PAUSED;
            // If paused for more than 15 seconds without user intervention, assume stuck/missing materials
            if (System.currentTimeMillis() - task.lastProgressAt > 15000) {
                AutonomousLogger.log("BUILD_STUCK", "Building paused for >15s. Likely missing materials or unreachable.");
                baritone.getPathingBehavior().cancelEverything();
                TaskRegistry.clearTask("stuck_or_missing_materials");
                AutonomousClient.onExternalTrigger("Building failed: Missing materials or area unreachable.");
            }
        } else {
            task.phase = TaskRegistry.Phase.COLLECTING;
            task.lastProgressAt = System.currentTimeMillis(); // Reset stuck timer while active
        }
    }

    private void handleHunting(baritone.api.utils.IPlayerContext ctx) {
        TaskRegistry.Task task = TaskRegistry.getActiveTask();
        if (task == null || !task.type.equals("mc_hunt")) return;
        if (task.lockedEntityId == null) return;

        // Find the target entity in the current world
        net.minecraft.world.entity.Entity target = null;
        for (net.minecraft.world.entity.Entity e : ctx.entities()) {
            if (e.getId() == task.lockedEntityId) {
                target = e;
                break;
            }
        }

        if (target == null || !target.isAlive()) {
            // Target is dead. Check for loot if we aren't already collecting
            if (task.phase != TaskRegistry.Phase.COLLECTING) {
                task.phase = TaskRegistry.Phase.COLLECTING;
                task.lastProgressTime = System.currentTimeMillis(); // Use for loot timeout
                AutonomousLogger.log("HUNT_LOOT", "Target dead. Scanning for drops...");
                baritone.getFollowProcess().cancel();
            }
            
            // Look for nearby items
            net.minecraft.world.entity.item.ItemEntity loot = ctx.entitiesStream()
                .filter(e -> e instanceof net.minecraft.world.entity.item.ItemEntity && e.distanceToSqr(ctx.player()) < 12 * 12)
                .map(e -> (net.minecraft.world.entity.item.ItemEntity)e)
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player())))
                .orElse(null);

            if (loot != null) {
                // Move to loot (update frequently)
                if (ctx.player().tickCount % 5 == 0) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(loot.blockPosition()));
                }
                return;
            } else if (System.currentTimeMillis() - task.lastProgressTime < 2000) {
                // Wait 2 seconds for drops to spawn/be detected
                return;
            }

            // No loot (or collected). Increment and find next.
            task.phase = TaskRegistry.Phase.SEARCHING;
            task.currentCount++;
            AutonomousLogger.log("HUNT_KILL", "Finished kill " + task.currentCount + "/" + task.targetQuantity);
            
            if (task.currentCount >= task.targetQuantity) {
                AutonomousLogger.log("HUNT_COMPLETE", "Hunt finished: " + task.targetQuantity + " " + task.targetSource);
                baritone.getPathingBehavior().cancelEverything();
                TaskRegistry.clearTask("success");
                AutonomousClient.onExternalTrigger("Successfully hunted " + task.targetQuantity + " " + task.targetSource + ".");
                return;
            }

            // Find next target
            net.minecraft.world.entity.Entity next = ctx.entitiesStream()
                .filter(e -> e.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equalsIgnoreCase(task.targetSource))
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player())))
                .orElse(null);

            if (next != null) {
                task.lockedEntityId = next.getId();
                AutonomousLogger.log("HUNT_NEXT", "Targeting next " + task.targetSource + " at " + next.blockPosition());
                baritone.getFollowProcess().follow(e -> e.getId() == next.getId());
            } else {
                AutonomousLogger.log("HUNT_PAUSED", "No more " + task.targetSource + " nearby. Waiting.");
                baritone.getPathingBehavior().cancelEverything();
                task.lockedEntityId = null;
            }
            return;
        }

        // Check distance for attack
        double distSq = ctx.player().distanceToSqr(target);
        if (distSq < 4.5 * 4.5) { // Attack reach
            // Timed attack: only swing every 10 ticks to avoid overkill jitter
            if (ctx.player().tickCount % 10 == 0) {
                ctx.minecraft().gameMode.attack(ctx.player(), target);
                ctx.player().swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private void handleVillageSearch(baritone.api.utils.IPlayerContext ctx) {
        TaskRegistry.Task task = TaskRegistry.getActiveTask();
        if (task == null || !task.type.equals("mc_find_village")) return;

        // Only scan every 40 ticks to reduce overhead and "jitter"
        if (ctx.player().tickCount % 40 != 0) return;

        // Timeout: 10 minutes max for village hunting
        if (System.currentTimeMillis() - task.startTime > 600000) {
            TaskRegistry.clearTask("timeout");
            return;
        }

        // 1. Scan for indicators
        VillageMarker marker = findBestVillageMarker(ctx);

        if (task.phase == TaskRegistry.Phase.SEARCHING) {
            if (marker != null) {
                // Lock onto the candidate
                task.lockedTarget = marker.pos;
                task.phase = TaskRegistry.Phase.MOVING_TO_CANDIDATE;
                task.targetSource = marker.type;
                task.lastProgressAt = System.currentTimeMillis();
                AutonomousLogger.log("VILLAGE_LOCK", "type=" + marker.type + " pos=" + marker.pos);
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(marker.pos));
            } else {
                baritone.api.IBaritone bt = BaritoneAPI.getProvider().getPrimaryBaritone();
                boolean active = bt.getCustomGoalProcess().isActive();
                
                // Watchdog: If we are active, check if we are actually moving
                if (active) {
                    baritone.api.pathing.goals.Goal goal = bt.getCustomGoalProcess().getGoal();
                    if (goal instanceof baritone.api.pathing.goals.GoalXZ) {
                        baritone.api.pathing.goals.GoalXZ gxz = (baritone.api.pathing.goals.GoalXZ) goal;
                        double dist = Math.sqrt(ctx.player().distanceToSqr(gxz.getX(), ctx.player().getY(), gxz.getZ()));
                        
                        if (dist < task.lastDist - 1.5) {
                            task.lastDist = dist;
                            task.lastProgressTime = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - task.lastProgressTime > 10000) {
                            // Stuck for 10 seconds! Force re-path
                            AutonomousLogger.log("VILLAGE_STUCK", "No progress for 10s. Forcing re-path.");
                            active = false; 
                        }
                    }
                }

                if (!active && !bt.getExploreProcess().isActive()) {
                    // Stable linear search: move 1000 blocks in current direction
                    // This is only called when we finish a path or get stuck, 
                    // so mouse jitter won't cause zig-zags during the journey.
                    float yaw = ctx.player().getYRot();
                    double rad = Math.toRadians(yaw);
                    int dx = (int) (-Math.sin(rad) * 1000);
                    int dz = (int) (Math.cos(rad) * 1000);
                    net.minecraft.core.BlockPos searchGoal = ctx.player().blockPosition().offset(dx, 0, dz);
                    
                    task.lastDist = 1000;
                    task.lastProgressTime = System.currentTimeMillis();
                    
                    AutonomousLogger.log("VILLAGE_EXPLORE", "Linear push to " + searchGoal.getX() + ", " + searchGoal.getZ());
                    bt.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalXZ(searchGoal.getX(), searchGoal.getZ()));
                }
            }
        } else if (task.phase == TaskRegistry.Phase.MOVING_TO_CANDIDATE) {
            // Check if we found something better (e.g. found a bell while moving to a bed)
            if (marker != null && isBetterMarker(marker.type, task.targetSource)) {
                task.lockedTarget = marker.pos;
                task.targetSource = marker.type;
                AutonomousLogger.log("VILLAGE_UPGRADE", "Found better marker: " + marker.type + " at " + marker.pos);
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(marker.pos));
            }
            
            // Ensure we are still moving
            baritone.api.IBaritone bt = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (!bt.getCustomGoalProcess().isActive()) {
                // We reached the candidate but maybe it wasn't the village center yet, or it failed
                // Re-evaluate or continue searching
                task.phase = TaskRegistry.Phase.SEARCHING;
            }
        }
    }

    private static class VillageMarker {
        net.minecraft.core.BlockPos pos;
        String type;
        VillageMarker(net.minecraft.core.BlockPos p, String t) { pos = p; type = t; }
    }

    private VillageMarker findBestVillageMarker(baritone.api.utils.IPlayerContext ctx) {
        // Priority: Bell > Job Site > Bed
        net.minecraft.core.BlockPos bell = scanForBlock(ctx, "bell", 112);
        if (bell != null) return new VillageMarker(bell, "bell");

        net.minecraft.core.BlockPos jobSite = scanForBlockGroup(ctx, new String[]{"composter", "lectern", "barrel", "brewing_stand", "cauldron", "fletching_table", "grindstone", "loom", "smithing_table", "smoker", "stonecutter", "blast_furnace"}, 96);
        if (jobSite != null) return new VillageMarker(jobSite, "job_site");

        net.minecraft.core.BlockPos bed = scanForBlock(ctx, "bed", 80);
        if (bed != null) return new VillageMarker(bed, "bed");

        return null;
    }

    private boolean isBetterMarker(String newType, String oldType) {
        if (newType.equals(oldType)) return false;
        if (newType.equals("bell")) return true;
        if (newType.equals("job_site") && oldType.equals("bed")) return true;
        return false;
    }

    private net.minecraft.core.BlockPos scanForBlockGroup(baritone.api.utils.IPlayerContext ctx, String[] patterns, int radius) {
        for (String p : patterns) {
            net.minecraft.core.BlockPos pos = scanForBlock(ctx, p, radius);
            if (pos != null) return pos;
        }
        return null;
    }

    private net.minecraft.core.BlockPos scanForBlock(baritone.api.utils.IPlayerContext ctx, String partialName, int radius) {
        net.minecraft.core.BlockPos startPos = ctx.player().blockPosition();
        baritone.utils.BlockStateInterface bsi = new baritone.utils.BlockStateInterface(ctx);

        for (int dx = -radius; dx <= radius; dx += 4) {
            for (int dy = -16; dy <= 16; dy += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    net.minecraft.core.BlockPos target = startPos.offset(dx, dy, dz);
                    net.minecraft.world.level.block.Block block = bsi.get0(target).getBlock();
                    String bName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
                    if (bName.contains(partialName)) {
                        return target;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void onPathEvent(PathEvent event) {
        if (event == lastEvent) {
            return;
        }
        lastEvent = event;

        String message;
        boolean significant = false;
        String releaseReason = null;
        switch (event) {
            case AT_GOAL:
                message = "Reached the specified goal";
                significant = true;
                releaseReason = "success";
                break;
            case CALC_FAILED:
                message = "Path calculation failed. Target might be unreachable.";
                significant = true;
                releaseReason = "failed";
                break;
            case CANCELED:
                message = "Task was canceled";
                significant = true;
                releaseReason = "canceled";
                break;
            default:
                return; // Ignore CALC_STARTED, CALC_FINISHED, etc.
        }

        baritone.api.utils.IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
        if (ctx == null || ctx.player() == null) {
            return;
        }
        String posStr = " at (" + ctx.player().blockPosition().getX() + ", " + ctx.player().blockPosition().getY() + ", " + ctx.player().blockPosition().getZ() + ")";
        
        if (significant) {
            TaskRegistry.Task active = TaskRegistry.getActiveTask();
            
            // Special handling for long-running state machine tasks:
            // Don't clear on AT_GOAL if we have our own success condition (like mc_hunt or mc_find_village)
            if (active != null) {
                // For HUNT and VILLAGE, we handle our own lifecycle. 
                // Only clear if the pathing actually FAILED irrecoverably.
                if (active.type.equals("mc_hunt") || active.type.equals("mc_find_village")) {
                    if (event != PathEvent.CALC_FAILED) {
                        return; 
                    }
                }
            }

            TaskRegistry.clearTask(releaseReason);
            AutonomousLogger.logEvent(event.name() + posStr);
            AutonomousClient.onExternalTrigger(message + posStr + ".");
        }
    }
}
