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

package org.strongback.components.ui;

import org.strongback.components.Switch;

/**
 * Defines an axis that points in a direction.
 */
@FunctionalInterface
public interface DirectionalAxis {
    public static enum DIRECTION {
        UP(0), RIGHT(90), DOWN(180), LEFT(270);

        int degree;

        DIRECTION(int degree) {
            this.degree = degree;
        }
    }

    /**
     * Get the direction that the axis is pointing in.
     *
     * @return the direction
     */
    public int getDirection();

    public default Switch getDirectionAsSwitch(DIRECTION direction) {
        return () -> this.getDirection() == direction.degree;
    }

    public default Switch getDirectionAsSwitch(int direction) {
        return () -> this.getDirection() == direction;
    }

    public default Switch getUp() {
        return this.getDirectionAsSwitch(DIRECTION.UP);
    }

    public default Switch getRight() {
        return this.getDirectionAsSwitch(DIRECTION.RIGHT);
    }

    public default Switch getDown() {
        return this.getDirectionAsSwitch(DIRECTION.DOWN);
    }

    public default Switch getLeft() {
        return this.getDirectionAsSwitch(DIRECTION.LEFT);
    }

}