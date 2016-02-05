/*
 * Strongback
 * Copyright 2015, Strongback and individual contributors by the @authors tag.
 * See the COPYRIGHT.txt in the distribution for a full listing of individual
 * contributors.
 *
 * Licensed under the MIT License; you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://opensource.org/licenses/MIT
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.strongback.hardware;

import org.strongback.components.Solenoid;

/**
 * Wrapper for WPILib {@link Solenoid}.
 *
 * @author Matthew Alonso
 * @see Solenoid
 * @see Hardware
 * @see edu.wpi.first.wpilibj.DoubleSolenoid
 */
final class HardwareSingleSolenoid implements Solenoid {
    private final edu.wpi.first.wpilibj.Solenoid solenoid;

    private Direction direction;

    HardwareSingleSolenoid(edu.wpi.first.wpilibj.Solenoid solenoid, Direction initialDirection) {
        assert solenoid != null;
        assert initialDirection != null;
        this.solenoid = solenoid;
        this.direction = initialDirection;
        solenoid.set(initialDirection == Direction.EXTENDING);
        checkState();
    }

    protected void checkState() {
        if (solenoid.get()) {
            direction = Direction.EXTENDING;
        } else if (!solenoid.get()) {
            direction = Direction.RETRACTING;
        }
    }

    @Override
    public Direction getDirection() {
        checkState();
        return direction;
    }

    @Override
    public HardwareSingleSolenoid extend() {
        solenoid.set(true);
        direction = Direction.EXTENDING;
        checkState();
        return this;
    }

    @Override
    public HardwareSingleSolenoid retract() {
        solenoid.set(false);
        direction = Direction.RETRACTING;
        checkState();
        return this;
    }

    @Override
    public String toString() {
        return "direction = " + direction;
    }
}