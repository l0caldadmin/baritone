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

package baritone.utils;

import baritone.api.utils.input.Input;
import net.minecraft.client.Options;
import net.minecraft.client.player.KeyboardInput;

public class PlayerMovementInput extends KeyboardInput {

    private final InputOverrideHandler handler;

    PlayerMovementInput(InputOverrideHandler handler, Options options) {
        super(options);
        this.handler = handler;
    }

    @Override
    public void tick() {
        // Baritone's Input enum values
        boolean forward = handler.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD);
        boolean backward = handler.isInputForcedDown(baritone.api.utils.input.Input.MOVE_BACK);
        boolean left = handler.isInputForcedDown(baritone.api.utils.input.Input.MOVE_LEFT);
        boolean right = handler.isInputForcedDown(baritone.api.utils.input.Input.MOVE_RIGHT);
        boolean jump = handler.isInputForcedDown(baritone.api.utils.input.Input.JUMP);
        boolean shift = handler.isInputForcedDown(baritone.api.utils.input.Input.SNEAK);
        boolean sprint = handler.isInputForcedDown(baritone.api.utils.input.Input.SPRINT);

        // Update the Input record (forward, backward, left, right, jump, shift, sprint)
        this.keyPresses = new net.minecraft.world.entity.player.Input(forward, backward, left, right, jump, shift, sprint);

        // Calculate impulses
        float forwardImpulse = (forward ? 1.0f : 0.0f) + (backward ? -1.0f : 0.0f);
        float leftImpulse = (left ? 1.0f : 0.0f) + (right ? -1.0f : 0.0f);

        // Update moveVector (Vec2 uses x for left/right impulse, y for forward/backward impulse)
        this.moveVector = new net.minecraft.world.phys.Vec2(leftImpulse, forwardImpulse).normalized();
    }
}
