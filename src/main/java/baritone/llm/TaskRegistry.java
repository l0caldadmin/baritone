package baritone.llm;

import baritone.api.utils.Helper;

/**
 * Tracks the currently active long-running task to prevent "pathing bounce"
 * and ensure task commitment.
 */
public class TaskRegistry {

    public enum Phase {
        SEARCHING,
        MOVING_TO_CANDIDATE,
        COLLECTING,
        PAUSED,
        STUCK,
        COMPLETED,
        FAILED
    }

    public static class Task {
        public final String type;
        public Phase phase;
        public net.minecraft.core.BlockPos lockedTarget;
        public String targetSource;
        public final long startTime;
        public long lastProgressAt;
        public int retargetCount = 0;
        public Integer lockedEntityId = null;
        public int targetQuantity = 1;
        public int currentCount = 0;
        public double lastDist = Double.MAX_VALUE;
        public long lastProgressTime = 0;

        public Task(String type) {
            this.type = type;
            this.phase = Phase.SEARCHING;
            this.startTime = System.currentTimeMillis();
            this.lastProgressAt = this.startTime;
        }
    }

    private static Task activeTask = null;

    /**
     * Attempts to start a new task.
     */
    public static synchronized boolean startTask(String taskName) {
        if (activeTask != null) {
            AutonomousLogger.log("REJECTED", "Cannot start " + taskName + " while " + activeTask.type + " is running.");
            return false;
        }
        activeTask = new Task(taskName);
        AutonomousLogger.log("TASK_START", taskName);
        Helper.HELPER.logDirect("[Bot] Starting task: " + taskName);
        TaskMemory.updateMemory(activeTask);
        return true;
    }

    /**
     * Clears the currently active task.
     */
    public static synchronized void clearTask(String reason) {
        if (activeTask != null) {
            AutonomousLogger.log("TASK_CLEAR", activeTask.type + " reason=" + reason);
            Helper.HELPER.logDirect("[Bot] Task cleared: " + activeTask.type + " (" + reason + ")");
            activeTask = null;
            TaskMemory.updateMemory(null);
        }
    }

    public static synchronized void syncMemory() {
        TaskMemory.updateMemory(activeTask);
    }

    public static synchronized Task getActiveTask() {
        return activeTask;
    }

    public static synchronized boolean hasActiveTask() {
        return activeTask != null;
    }
}
