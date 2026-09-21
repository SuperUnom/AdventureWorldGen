package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.plan.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePlanRegistryTest {
    @Test void releasingAnIsolatedPlanPreservesOtherProfilesAndNewerPublications() {
        var a=new ContentId("test:release_a");var b=new ContentId("test:release_b");
        var first=new View();var other=new View();var replacement=new View();
        try {
            RuntimePlanRegistry.start(a,()->first).join();RuntimePlanRegistry.start(b,()->other).join();
            RuntimePlanRegistry.release(a,first);
            assertThrows(IllegalStateException.class,()->RuntimePlanRegistry.await(a));
            assertSame(other,RuntimePlanRegistry.await(b));
            RuntimePlanRegistry.start(a,()->replacement).join();
            RuntimePlanRegistry.release(a,first);
            assertSame(replacement,RuntimePlanRegistry.await(a));
        } finally {
            RuntimePlanRegistry.release(a,first);RuntimePlanRegistry.release(a,replacement);RuntimePlanRegistry.release(b,other);
        }
    }
    private static final class View implements AdventurePlanView {
        public ContentId biomeAt(int x,int y,int z){return new ContentId("test:plain");}
        public MacroSample terrainAt(double x,double z){return new MacroSample(64,Double.NaN,WaterKind.NONE,false,"plain","plain","test");}
        public List<PlannedStructurePlacement> plannedStructures(){return List.of();}
        public SpawnPosition spawnPosition(){return new SpawnPosition(.5,65,.5,0);}
    }
}
