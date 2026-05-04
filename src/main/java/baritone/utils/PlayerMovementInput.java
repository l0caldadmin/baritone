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

    // Input internals changed in 1.21.x; this class now acts as a lightweight marker
    // so InputOverrideHandler can swap to a custom input implementation safely.
}
