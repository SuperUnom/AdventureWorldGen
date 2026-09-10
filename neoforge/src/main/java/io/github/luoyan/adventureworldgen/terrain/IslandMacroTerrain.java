package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;

import java.util.Objects;

/** Coast and regional terrain composition before inland hydrology is applied. */
public final class IslandMacroTerrain implements MacroTerrain {
    private final Coastline coastline;
    private final RegionTerrain regions;
    private final double seaSurface;
    private final double landBand;
    private final double seaBand;
    private final OceanBathymetry bathymetry;
    private final String version;
    private final io.github.luoyan.adventureworldgen.spatial.ColumnQueryCache<Byte> coastTiles =
            new io.github.luoyan.adventureworldgen.spatial.ColumnQueryCache<>(16384);
    private record CoastTile(int x,int z,byte saturation) {}
    private final ThreadLocal<CoastTile> lastCoastTile=new ThreadLocal<>();

    private double coastDistance(double x,double z) {
        if(!(landBand>0&&seaBand>0))return coastline.signedDistance(x,z);
        int tx=(int)Math.floor(x/64),tz=(int)Math.floor(z/64);
        CoastTile tile=lastCoastTile.get();
        if(tile==null||tile.x!=tx||tile.z!=tz) {
            byte saturated=coastTiles.get(tx,tz,(gx,gz)-> {
                double distance=coastline.signedDistance(gx*64.0+32,gz*64.0+32);
                // Distance to a closed boundary is 1-Lipschitz. The extra margin
                // keeps floating-point boundary cases on the exact-query path.
                double reach=StrictMath.sqrt(2)*32+1e-8;
                return (byte)(distance>landBand+reach?1:distance< -OceanBathymetry.extent(seaBand)-reach?-1:0);
            });
            tile=new CoastTile(tx,tz,saturated);lastCoastTile.set(tile);
        }
        return tile.saturation>0?landBand:tile.saturation<0?-OceanBathymetry.extent(seaBand):coastline.signedDistance(x,z);
    }

    public IslandMacroTerrain(Coastline coastline, RegionTerrain regions, long seed, double seaSurface,
                              double landBand, double seaBand, String version) {
        this.coastline = Objects.requireNonNull(coastline, "coastline");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.seaSurface = seaSurface;
        this.landBand = landBand;
        this.seaBand = seaBand;
        this.bathymetry = new OceanBathymetry(seed);
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public MacroSample sample(double x, double z) {
        double signedDistance = coastDistance(x, z);
        RegionTerrain.Sample region = regions.sample(x, z);
        if (signedDistance >= 0.0) {
            double blend = smooth(clamp(signedDistance / landBand));
            double relative = StrictMath.max(0.0, region.relativeHeight());
            return new MacroSample(seaSurface + blend * relative, Double.NaN, WaterKind.NONE, false,
                    region.regionId(), region.template().name().toLowerCase(java.util.Locale.ROOT), version,
                    region.recipe().id(),region.secondary()==null?"":region.secondary().id(),region.secondaryWeight(),region.mountainInfluence(),0,0,0);
        }
        double depth = bathymetry.depth(x,z,-signedDistance,seaBand);
        return new MacroSample(seaSurface - depth, seaSurface, WaterKind.OCEAN, false,
                region.regionId(), region.template().name().toLowerCase(java.util.Locale.ROOT), version,
                    region.recipe().id(),region.secondary()==null?"":region.secondary().id(),region.secondaryWeight(),region.mountainInfluence(),0,0,0);
    }

    private static double clamp(double value) { return StrictMath.max(0.0, StrictMath.min(1.0, value)); }
    private static double smooth(double value) { return value * value * (3.0 - 2.0 * value); }
}
