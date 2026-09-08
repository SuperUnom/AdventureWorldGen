package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoastlineSearchTest {
    @Test void spatialSearchMatchesExhaustiveSegmentsIncludingTies() {
        var random = new Random(7331);
        var points = new ArrayList<Vec2>();
        for (int i = 0; i < 257; i++) {
            double angle = i * 2 * StrictMath.PI / 257, radius = 100 + random.nextDouble() * 100;
            points.add(new Vec2(radius * StrictMath.cos(angle), radius * StrictMath.sin(angle)));
        }
        var coast = new Coastline(points);
        for (int i = 0; i < 2000; i++) check(coast, random.nextDouble()*800-400, random.nextDouble()*800-400);
        for (var point : points) check(coast, point.x(), point.z());
        var square = new Coastline(List.of(new Vec2(-10,-10), new Vec2(10,-10),
                new Vec2(10,10), new Vec2(-10,10)));
        check(square, 0, 0);
        check(square, 10, -10);
        check(square, -10, -10);
    }

    private static void check(Coastline coast, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        int segment = -1;
        Vec2 nearest = null;
        var vertices = coast.vertices();
        for (int i = 0; i < vertices.size(); i++) {
            var a = vertices.get(i); var b = vertices.get((i+1)%vertices.size());
            double dx = b.x()-a.x(), dz = b.z()-a.z();
            double t = StrictMath.max(0, StrictMath.min(1, ((x-a.x())*dx+(z-a.z())*dz)/(dx*dx+dz*dz)));
            var p = new Vec2(a.x()+t*dx, a.z()+t*dz);
            double px=x-p.x(), pz=z-p.z(), distance=px*px+pz*pz;
            if (distance < best) { best=distance; segment=i; nearest=p; }
        }
        var actual = coast.nearestPoint(x,z);
        assertEquals(segment, actual.segmentIndex());
        assertEquals(nearest, actual.point());
        assertEquals(StrictMath.sqrt(best), actual.distance());
    }
}
