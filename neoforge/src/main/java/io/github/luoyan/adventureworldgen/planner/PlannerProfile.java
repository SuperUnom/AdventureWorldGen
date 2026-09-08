package io.github.luoyan.adventureworldgen.planner;

import java.util.List;

/** Every non-author-tunable constant that affects planner-v2 output. */
public record PlannerProfile(
        String algorithmVersion,
        String planFormatVersion,
        String hydrologyVersion,
        int finalAreaCellSide,
        List<Integer> areaCellSides,
        List<Integer> costGridSpacings,
        int costEdgeSampleSpacing,
        double levelTolerance,
        Terrain terrain,
        Coast coast,
        Search search
) {
    public static final PlannerProfile V2 = new PlannerProfile(
            "planner-v2",
            "plan-v2",
            io.github.luoyan.adventureworldgen.hydrology.RiverMorphology.VERSION,
            4,
            List.of(16, 8, 4),
            List.of(16, 8),
            8,
            0.35,
            new Terrain(768, 0.38, 480, 180, List.of(35, 35, 15, 15), 16, 8),
            new Coast(512, 4096, 16_384, 64, 16_384, 128, 256),
            new Search(
                    2_000_000, 16_000_000, 1L << 30,
                    List.of(256, 1024, 4096, Integer.MAX_VALUE),
                    2_000_000, 200_000, 64,
                    200_000, 1_000_000,
                    128, 2_000, 10_000, 40_000, 200_000,
                    200_000, 20_000, 100_000,
                    100_000_000, 100_000_000, 100_000_000,
                    4, 8, 16));

    public PlannerProfile {
        areaCellSides = List.copyOf(areaCellSides);
        costGridSpacings = List.copyOf(costGridSpacings);
    }

    public int maximumCostNodes() {
        return search.maximumCostNodes();
    }

    public int maximumAreaCells() {
        return search.maximumAreaCells();
    }

    public long maximumWorkingMemoryBytes() {
        return search.maximumWorkingMemoryBytes();
    }

    public record Terrain(int regionSpacing, double maximumRegionJitterFraction,
                          int coordinateWarpScale, int coordinateWarpAmplitude,
                          List<Integer> templateWeights, int detailRecoveryDistance,
                          int maximumSupportDelta) {
        public Terrain {
            templateWeights = List.copyOf(templateWeights);
        }
    }

    public record Coast(int initialResolution, int maximumResolution, int maximumVertices,
                        int targetArcSampleSpacing, int maximumArcSamples,
                        int maximumLandBand, int maximumSeaBand) {
        public double maximumPolylineError(double radius) {
            return StrictMath.min(2.0, radius / 1024.0);
        }

        public double landBand(double radius) {
            return StrictMath.min(maximumLandBand, radius / 8.0);
        }

        public double seaBand(double radius) {
            return StrictMath.min(maximumSeaBand, radius / 4.0);
        }
    }

    public record Search(int maximumCostNodes, int maximumAreaCells, long maximumWorkingMemoryBytes,
                         List<Integer> retainedCandidates, long requiredCandidatePreparations,
                         long optionalCandidatePreparations, int spawnCandidateAttempts,
                         long requiredJointStates, long requiredAreaStates, int optionalAdditionAttempts,
                         int optionalJointStatesPerAttempt, int optionalAreaStatesPerAttempt,
                         long optionalJointStatesTotal, long optionalAreaStatesTotal,
                         long extraAreaAttempts, long scatterAttempts, long boundaryAttempts,
                         long requiredFloodVisits, long optionalFloodVisits, long finalValidationVisits,
                         int maximumTerrainRepairVersions, int maximumSupportDelta, int supportFadeDistance) {
        public Search {
            retainedCandidates = List.copyOf(retainedCandidates);
        }
    }
}
