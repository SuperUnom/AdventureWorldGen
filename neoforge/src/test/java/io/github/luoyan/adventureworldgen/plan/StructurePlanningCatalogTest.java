package io.github.luoyan.adventureworldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePlanningCatalogTest {
    @Test
    void knownIdsProduceStablePurePlanningInfoAndUnknownIdsAreAbsent() {
        var known = new ContentId("test:known");
        var catalog = StructurePlanningCatalog.fromIds(List.of(known));

        assertEquals(new StructurePlanningInfo(known), catalog.find(known).orElseThrow());
        assertEquals(catalog.find(known), catalog.find(known));
        assertTrue(catalog.find(new ContentId("test:unknown")).isEmpty());
        assertEquals(List.of(ContentId.class),
                List.of(StructurePlanningInfo.class.getRecordComponents()[0].getType()));
    }
}
