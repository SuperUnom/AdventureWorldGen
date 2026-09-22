package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.RoadWorkControl;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class RoadWorkBudgetTest {
    @Test void tasksAndNestedConstructionHaveIndependentBatches() {
        var pauses=new ArrayList<RoadWorkControl.Pause>();
        var budget=new RoadWorkBudget(3,pauses::add);
        budget.task("first");for(int i=0;i<3;i++)budget.operation();
        budget.task("second");for(int i=0;i<3;i++)budget.operation();
        try(var phase=budget.phase("construction")) {
            for(int i=0;i<3;i++)budget.operation();
            assertTrue(pauses.isEmpty());budget.operation();
        }
        budget.operation();
        assertEquals(java.util.List.of("second/construction","second"),pauses.stream().map(RoadWorkControl.Pause::task).toList());
        assertTrue(pauses.stream().allMatch(p->p.used()==3&&p.limit()==3));
    }
    @Test void aControllerCannotGrantPermissionToExceedStorageCaps() {
        var budget=new RoadWorkBudget(3,p->{});
        assertThrows(IllegalStateException.class,()->budget.capacity(RoadWorkControl.Kind.SEARCH_STATES,100_000,100_000));
    }
}
