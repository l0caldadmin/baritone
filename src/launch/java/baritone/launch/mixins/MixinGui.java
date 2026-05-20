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

import baritone.llm.TaskRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class MixinGui {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void onExtractRenderState(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        TaskRegistry.Task task = TaskRegistry.getActiveTask();
        if (task == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        int y = 5;
        
        // Render Bot Task Status
        String taskStr = "§dBot Task:§f " + task.type.toUpperCase();
        guiGraphics.text(mc.font, taskStr, 5, y, 0xFFFFFF);
        y += 10;
        
        String phaseStr = "§7Phase:§f " + task.phase.name();
        guiGraphics.text(mc.font, phaseStr, 8, y, 0xFFFFFF);
        
        if (task.lockedTarget != null) {
            y += 10;
            String targetStr = "§7Target:§f " + task.lockedTarget.getX() + ", " + task.lockedTarget.getY() + ", " + task.lockedTarget.getZ();
            guiGraphics.text(mc.font, targetStr, 8, y, 0xFFFFFF);
        }
    }
}
