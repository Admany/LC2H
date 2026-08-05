package org.admany.lc2h.worldgen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountainCityReservationPlannerTest {

    private static final int SIDE = 48;

    @Test
    void cityReservationHasBothProportionalAndAbsoluteBounds() {
        assertTrue(MountainCityReservationPlanner.boundedBudget(0) == 0);
        assertTrue(MountainCityReservationPlanner.boundedBudget(10) <= 2);
        assertTrue(MountainCityReservationPlanner.boundedBudget(256) <= 24);
    }

    @Test
    void tunnelRequiresTwoPortalsAndContinuousSideCover() {
        int[] heights = new int[SIDE * SIDE];
        boolean[] elevated = new boolean[SIDE * SIDE];
        int[] highway = new int[SIDE * SIDE];
        Arrays.fill(heights, 90);
        Arrays.fill(highway, -1);

        int z = 16;
        for (int x = 13; x <= 19; x++) {
            highway[index(x, z)] = 0;
        }
        for (int x = 14; x <= 18; x++) {
            elevated[index(x, z)] = true;
            elevated[index(x, z - 1)] = true;
            elevated[index(x, z + 1)] = true;
        }

        assertTrue(MountainCityReservationPlanner.enclosedTunnel(
            16, z, true, heights, elevated, highway, 71));

        elevated[index(17, z + 1)] = false;
        assertFalse(MountainCityReservationPlanner.enclosedTunnel(
            16, z, true, heights, elevated, highway, 71));
    }

    @Test
    void tunnelCannotEndInsideTheMountainOrAtAnUncoveredSide() {
        int[] heights = new int[SIDE * SIDE];
        boolean[] elevated = new boolean[SIDE * SIDE];
        int[] highway = new int[SIDE * SIDE];
        Arrays.fill(heights, 100);
        Arrays.fill(highway, -1);

        int z = 16;
        for (int x = 13; x <= 19; x++) {
            highway[index(x, z)] = 0;
            elevated[index(x, z)] = true;
            elevated[index(x, z - 1)] = true;
            elevated[index(x, z + 1)] = true;
        }

        assertFalse(MountainCityReservationPlanner.enclosedTunnel(
            16, z, true, heights, elevated, highway, 71));
    }

    @Test
    void compactSelectionCannotJumpBetweenMountainIslands() {
        int side = 16;
        boolean[] eligible = new boolean[side * side];
        int[] heights = new int[side * side];
        Arrays.fill(heights, 80);

        // A deep 7x7 mountain and a disconnected but slightly taller 2x2 knob.
        fill(eligible, side, 2, 2, 8, 8);
        fill(eligible, side, 12, 12, 13, 13);
        for (int z = 12; z <= 13; z++) {
            for (int x = 12; x <= 13; x++) {
                heights[z * side + x] = 140;
            }
        }

        boolean[] selected = MountainCityReservationPlanner.compactSelection(
            eligible, heights, side, 24);

        assertEquals(24, count(selected));
        assertEquals(24, connectedCount(selected, side));
        for (int z = 12; z <= 13; z++) {
            for (int x = 12; x <= 13; x++) {
                assertFalse(selected[z * side + x],
                    "selection must not jump to a disconnected high-scoring island");
            }
        }
    }

    @Test
    void compactSelectionBuildsTransitionShellAroundItsCore() {
        int side = 16;
        boolean[] eligible = new boolean[side * side];
        int[] heights = new int[side * side];
        Arrays.fill(heights, 100);
        fill(eligible, side, 2, 2, 12, 12);

        boolean[] selected = MountainCityReservationPlanner.compactSelection(
            eligible, heights, side, 24);
        int[] depth = MountainCityReservationPlanner.selectedDepth(selected, side);

        assertEquals(24, connectedCount(selected, side));
        assertTrue(Arrays.stream(depth).anyMatch(value -> value == 0),
            "a compact patch needs an outer transition shell");
        assertTrue(Arrays.stream(depth).anyMatch(value -> value >= 1),
            "a compact patch needs an interior with less city shaping");
    }

    private static void fill(boolean[] values, int side, int minX, int minZ, int maxX, int maxZ) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                values[z * side + x] = true;
            }
        }
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static int connectedCount(boolean[] selected, int side) {
        int first = -1;
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return 0;
        }
        boolean[] visited = new boolean[selected.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        visited[first] = true;
        queue.add(first);
        int count = 0;
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            count++;
            int x = current % side;
            int z = current / side;
            if (x > 0) {
                visit(current - 1, selected, visited, queue);
            }
            if (x + 1 < side) {
                visit(current + 1, selected, visited, queue);
            }
            if (z > 0) {
                visit(current - side, selected, visited, queue);
            }
            if (z + 1 < side) {
                visit(current + side, selected, visited, queue);
            }
        }
        return count;
    }

    private static void visit(int index,
                              boolean[] selected,
                              boolean[] visited,
                              ArrayDeque<Integer> queue) {
        if (selected[index] && !visited[index]) {
            visited[index] = true;
            queue.addLast(index);
        }
    }

    private static int index(int x, int z) {
        return z * SIDE + x;
    }
}
