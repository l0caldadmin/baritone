package baritone.api.plugins.hooks;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import java.util.Optional;

/**
 * Hook for distant chunkloading (e.g. Distant Horizons).
 * Allows Baritone to query block data for chunks that are not loaded in the client.
 */
public interface IDistantChunkProvider {

    /**
     * Get the block state at a position in a distant chunk.
     *
     * @param pos The position to query.
     * @return The block state, if available.
     */
    Optional<BlockState> getBlockState(BlockPos pos);

    /**
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return Whether this provider has data for the specified chunk.
     */
    boolean hasChunk(int chunkX, int chunkZ);
}
