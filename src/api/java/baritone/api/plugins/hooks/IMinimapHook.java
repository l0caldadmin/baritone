package baritone.api.plugins.hooks;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Optional;

/**
 * Hook for minimap integration.
 */
public interface IMinimapHook {

    /**
     * @return The current path being followed by Baritone, if any.
     */
    Optional<List<BlockPos>> getCurrentPath();

    /**
     * @return The current goal of Baritone, if any.
     */
    Optional<BlockPos> getCurrentGoal();

    /**
     * Set a new goal from the minimap.
     *
     * @param pos The block position to go to.
     */
    void setGoal(BlockPos pos);

    /**
     * Clear the current goal.
     */
    void clearGoal();
}
