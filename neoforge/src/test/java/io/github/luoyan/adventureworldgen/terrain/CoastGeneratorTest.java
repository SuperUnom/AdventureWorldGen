package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoastGeneratorTest {
    private final CoastGenerator generator = new CoastGenerator(PlannerProfile.V2);

    @Test
    void coastlineRetainsRoughnessAtNestedSmallScales() {
        var coast = generator.generate(345705185492107788L, 3000, 288).coastline();
        double fine = perimeter(coast.equalArcSamples(16, 50000));
        double middle = perimeter(coast.equalArcSamples(64, 50000));
        double coarse = perimeter(coast.equalArcSamples(256, 50000));
        assertTrue(fine > middle * 1.06, "small-scale shore became a smooth curve");
        assertTrue(middle > coarse * 1.06, "bays lack smaller inlets and capes");
    }

    @Test
    void fractalDetailPreservesOrientation() {
        var warp = new FractalCoastWarp(345705185492107788L, 3000);
        double epsilon = 0.0001;
        for (int z=-3000;z<=3000;z+=137) for(int x=-3000;x<=3000;x+=139) {
            var p=warp.apply(x,z); var dx=warp.apply(x+epsilon,z); var dz=warp.apply(x,z+epsilon);
            double determinant=((dx.x()-p.x())*(dz.z()-p.z())-(dx.z()-p.z())*(dz.x()-p.x()))/(epsilon*epsilon);
            assertEquals(1,determinant,0.02,"coast detail folds or collapses a local area");
        }
    }

    private static double perimeter(java.util.List<io.github.luoyan.adventureworldgen.spatial.Vec2> points) {
        double result=0;
        for(int i=0;i<points.size();i++) result+=points.get(i).distance(points.get((i+1)%points.size()));
        return result;
    }

    @Test
    void isDeterministicBoundedAndContainsReservation() {
        var first = generator.generate(8844, 6000, 300);
        var second = generator.generate(8844, 6000, 300);
        assertEquals(first.coastline().vertices(), second.coastline().vertices());
        assertArrayEquals(first.phases(), second.phases());
        assertEquals(first.vertexCount(), second.vertexCount());
        assertTrue(first.estimatedMaximumError() <= 2.0);
        for (var vertex : first.coastline().vertices()) {
            double radius = StrictMath.hypot(vertex.x(), vertex.z());
            assertTrue(radius >= 300.0 - 1e-8 && radius <= 6000.0 + 1e-8);
        }
        for (int i = 0; i < 360; i++) {
            double angle = i * StrictMath.PI / 180.0;
            assertTrue(first.coastline().contains(300 * StrictMath.cos(angle), 300 * StrictMath.sin(angle)));
        }
    }

    @Test
    void exactDistanceAndArcSamplesUseTheFrozenPolyline() {
        var result = generator.generate(42, 1000, 100);
        var coast = result.coastline();
        var vertex = coast.vertices().getFirst();
        assertEquals(0.0, coast.signedDistance(vertex.x(), vertex.z()), 1e-8);
        assertTrue(coast.signedDistance(0, 0) > 100);
        assertTrue(coast.signedDistance(2000, 0) < 0);
        assertTrue(result.equalArcSamples().size() >= 3);
        assertNotEquals(result.equalArcSamples().getFirst(), result.equalArcSamples().getLast());
        for (var sample : result.equalArcSamples()) {
            assertEquals(0.0, coast.signedDistance(sample.x(), sample.z()), 1e-7);
        }
    }

    @Test
    void rejectsReservationThatTouchesConservativeInnerRadius() {
        var failure = assertThrows(PlanningFailure.class, () -> generator.generate(1, 1000, 750));
        assertEquals(PlanningFailure.Code.CONFIG_CONFLICT, failure.code());
    }
}
