/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.RenderEvent;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Brady
 * @since 2/13/2020
 */
@Mixin(LevelRenderer.class)
public class MixinWorldRenderer {

    // MC 26.1.2: renderLevel signature changed — Camera + 3x Matrix4f replaced by CameraRenderState + Matrix4fc,
    // and a new ChunkSectionsToRender parameter was added at the end.
    @Inject(
            method = "renderLevel",
            at = @At("RETURN")
    )
    private void onRenderLevel(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean renderBlockOutline, CameraRenderState cameraRenderState, Matrix4fc frustumMatrix, GpuBufferSlice gpuBufferSlice, Vector4f fogColor, boolean forcedCamera, ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
        PoseStack modelViewStack = new PoseStack();
        // In 26.1.2, the model-view matrix is available as viewRotationMatrix on CameraRenderState
        modelViewStack.last().pose().set(cameraRenderState.viewRotationMatrix);
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            ibaritone.getGameEventHandler().onRenderPass(new RenderEvent(partialTicks, modelViewStack, cameraRenderState.projectionMatrix));
        }
    }
}