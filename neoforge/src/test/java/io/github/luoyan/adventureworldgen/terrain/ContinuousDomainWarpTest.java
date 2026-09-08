package io.github.luoyan.adventureworldgen.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContinuousDomainWarpTest {
    @Test void detailNeverFoldsCoordinatesAcrossWorldSeeds() {
        for (long seed : new long[]{7331, 345705185492107788L, -9}) {
            var warp = new ContinuousDomainWarp(seed, "boundary-test", 1);
            for (int x = -1800; x <= 1800; x += 23) for (int z = -1800; z <= 1800; z += 29) {
                var p = warp.apply(x, z); var px = warp.apply(x + 0.01, z); var pz = warp.apply(x, z + 0.01);
                double determinant = ((px.x() - p.x()) * (pz.z() - p.z()) - (px.z() - p.z()) * (pz.x() - p.x())) / 0.0001;
                assertTrue(determinant > 0.05, "folded or nearly collapsed boundary at " + x + "," + z);
                assertTrue(p.distance(px) < 0.03 && p.distance(pz) < 0.03, "boundary develops a narrow stretched tooth");
            }
        }
    }
    @Test void localDetailBendsSixtyFourBlockSegments() {
        var warp = new ContinuousDomainWarp(345705185492107788L, "coast-detail-r8", 1);
        int bends = 0;
        for (int x = -2500; x < 2500; x += 64) {
            var a = warp.apply(x, 600); var b = warp.apply(x + 64, 600); var mid = warp.apply(x + 32, 600);
            double displacement = Math.abs(mid.z() - (a.z() + b.z()) * 0.5);
            if (displacement > 1.5) bends++;
        }
        assertTrue(bends >= 15, "fine shoreline detail vanished");
    }
}
