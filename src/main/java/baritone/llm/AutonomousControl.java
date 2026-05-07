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

public class AutonomousControl implements AbstractGameEventListener {

    private static boolean registered = false;
    private final IBaritone baritone;
    private PathEvent lastEvent = null;
    private long lastEventTime = 0;

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

    private void handleVillageSearch(baritone.api.utils.IPlayerContext ctx) {
        TaskRegistry.Task task = TaskRegistry.getActiveTask();
        if (task == null || !task.type.equals("mc_find_village")) return;

        // Only scan every 20 ticks to avoid lag
        if (ctx.player().tickCount % 20 != 0) return;

        // Simple timeout: 5 minutes max for searching/moving
        if (System.currentTimeMillis() - task.startTime > 300000) {
            TaskRegistry.clearTask("timeout");
            return;
        }

        if (task.phase == TaskRegistry.Phase.SEARCHING) {
            net.minecraft.core.BlockPos found = findVillageIndicator(ctx);
            if (found != null) {
                task.lockedTarget = found;
                task.phase = TaskRegistry.Phase.MOVING_TO_CANDIDATE;
                task.targetSource = "indicator_found";
                task.lastProgressAt = System.currentTimeMillis();
                AutonomousLogger.log("VILLAGE_TARGET_LOCKED", "pos=" + found.getX() + "," + found.getY() + "," + found.getZ());
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(found));
            }
        } else if (task.phase == TaskRegistry.Phase.MOVING_TO_CANDIDATE) {
            // Enforcement: Ensure Baritone is still pathing to the locked target
            IBaritone bt = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (!bt.getCustomGoalProcess().isActive()) {
                AutonomousLogger.log("VILLAGE_RETARGET_BLOCKED", "reason=task_locked target=" + task.lockedTarget);
                bt.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(task.lockedTarget));
            }
        }
    }

    private net.minecraft.core.BlockPos findVillageIndicator(baritone.api.utils.IPlayerContext ctx) {
        net.minecraft.core.BlockPos startPos = ctx.player().blockPosition();
        baritone.utils.BlockStateInterface bsi = new baritone.utils.BlockStateInterface(ctx);
        
        // Priority 1: Bells (highest confidence)
        net.minecraft.core.BlockPos bell = scanForBlock(ctx, "bell", 96);
        if (bell != null) return bell;

        // Priority 2: Beds/Lecterns
        net.minecraft.core.BlockPos secondary = scanForBlock(ctx, "bed", 96);
        if (secondary == null) secondary = scanForBlock(ctx, "lectern", 96);
        if (secondary != null) return secondary;

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
            if (active != null && active.type.equals("mc_find_village") && active.phase == TaskRegistry.Phase.MOVING_TO_CANDIDATE) {
                AutonomousLogger.log("VILLAGE_TARGET_RELEASED", "reason=" + releaseReason + " pos=" + active.lockedTarget);
            }
            TaskRegistry.clearTask(releaseReason);
            AutonomousLogger.logEvent(event.name() + posStr);
            AutonomousClient.onExternalTrigger(message + posStr + ".");
        }
    }
}
