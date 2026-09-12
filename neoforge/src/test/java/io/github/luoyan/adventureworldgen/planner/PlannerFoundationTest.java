package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AreaRange;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.spatial.AreaGrid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.plan.StableIds;

class PlannerFoundationTest {
    @Test
    void areaRangeQuantizesInward() {
        var cells = new AreaRange(17, 287).inCells(4);
        assertEquals(2, cells.min());
        assertEquals(17, cells.max());
        assertTrue(new AreaRange(17, 31).inCells(16).isEmpty());
    }

    @Test
    void finalGridFloorsNegativeCoordinates() {
        assertEquals(new AreaGrid.Cell(0, 0), AreaGrid.cellAtBlock(0, 3));
        assertEquals(new AreaGrid.Cell(-1, -1), AreaGrid.cellAtBlock(-1, -4));
        assertEquals(new AreaGrid.Cell(-2, -2), AreaGrid.cellAtBlock(-5, -8));
        assertEquals(-6.0, new AreaGrid.Cell(-2, 0).sampleX());
    }

    @Test
    void stableIdsDoNotRenumberAndSamplesDependOnEveryKeyField() {
        ContentId id = new ContentId("example:ruins");
        assertEquals("instance/example:ruins/0", StableIds.structureInstance(id, 0));
        assertEquals("instance/example:ruins/3", StableIds.structureInstance(id, 3));

        double first = DeterministicRandom.sample(42, "planner-v2", "candidates", "required/0", 7);
        assertEquals(first, DeterministicRandom.sample(42, "planner-v2", "candidates", "required/0", 7));
        assertNotEquals(first, DeterministicRandom.sample(42, "planner-v2", "candidates", "required/0", 8));
        assertTrue(first >= 0.0 && first < 1.0);
    }
}
