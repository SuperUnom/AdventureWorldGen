package io.github.luoyan.adventureworldgen.plan;

import io.github.luoyan.adventureworldgen.spatial.AreaGrid;

import java.util.List;

/**
 * Every non-author-tunable constant that affects planner-v2 output, together with the version
 * identities that make a plan addressable. Publishing this from the plan package lets generators,
 * persistence and runtime share one parameter/version contract without depending on planner.
 */
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
    /**
     * Default budget of the competitive-growth stage, counted in biome/cell eligibility operations
     * - not in cells, candidates or wall-clock time. It used to be a bare
     * {@code BUDGET = 12_000_000} inside {@code BiomeAllocationPlanner}, where a reader could not
     * tell what was being counted; the value is unchanged.
     */
    public static final long COMPETITIVE_GROWTH_OPERATIONS = 12_000_000L;

    public static final PlannerProfile V2 = new PlannerProfile(
            "planner-v2",
            "plan-v3",
            PlanVersions.HYDROLOGY,
            AreaGrid.CELL_SIDE,
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
                    4, 8, 16,
                    COMPETITIVE_GROWTH_OPERATIONS));

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

    /**
     * The same algorithm and version identities with different resource budgets. Used to inject a
     * profile whose budget is small enough to observe, without inventing a second algorithm: only
     * the two budget fields change, and they are already part of the algorithm's declared inputs.
     */
    public PlannerProfile withBudgets(int maximumCostNodes, long maximumWorkingMemoryBytes) {
        return withSearch(search.withBudgets(maximumCostNodes, maximumWorkingMemoryBytes));
    }

    /** The same parameters with a different competitive-growth operation budget. */
    public PlannerProfile withCompetitiveGrowthOperations(long operations) {
        return withSearch(search.withCompetitiveGrowthOperations(operations));
    }

    /** The same parameters with a different algorithm identity marker. */
    public PlannerProfile withAlgorithmVersion(String algorithmVersion) {
        return new PlannerProfile(algorithmVersion, planFormatVersion, hydrologyVersion, finalAreaCellSide,
                areaCellSides, costGridSpacings, costEdgeSampleSpacing, levelTolerance, terrain, coast, search);
    }

    /** The same parameters with a different terrain tuning record. */
    public PlannerProfile withTerrain(Terrain terrain) {
        return new PlannerProfile(algorithmVersion, planFormatVersion, hydrologyVersion, finalAreaCellSide,
                areaCellSides, costGridSpacings, costEdgeSampleSpacing, levelTolerance, terrain, coast, search);
    }

    private PlannerProfile withSearch(Search search) {
        return new PlannerProfile(algorithmVersion, planFormatVersion, hydrologyVersion, finalAreaCellSide,
                areaCellSides, costGridSpacings, costEdgeSampleSpacing, levelTolerance, terrain, coast, search);
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
                         int maximumTerrainRepairVersions, int maximumSupportDelta, int supportFadeDistance,
                         long competitiveGrowthOperations) {
        public Search {
            retainedCandidates = List.copyOf(retainedCandidates);
        }

        /** The same search parameters with a different competitive-growth operation budget. */
        public Search withCompetitiveGrowthOperations(long operations) {
            return new Search(maximumCostNodes, maximumAreaCells, maximumWorkingMemoryBytes, retainedCandidates,
                    requiredCandidatePreparations, optionalCandidatePreparations, spawnCandidateAttempts,
                    requiredJointStates, requiredAreaStates, optionalAdditionAttempts, optionalJointStatesPerAttempt,
                    optionalAreaStatesPerAttempt, optionalJointStatesTotal, optionalAreaStatesTotal, extraAreaAttempts,
                    scatterAttempts, boundaryAttempts, requiredFloodVisits, optionalFloodVisits, finalValidationVisits,
                    maximumTerrainRepairVersions, maximumSupportDelta, supportFadeDistance, operations);
        }

        /** The same search parameters with different resource budgets. */
        public Search withBudgets(int maximumCostNodes, long maximumWorkingMemoryBytes) {
            return new Search(maximumCostNodes, maximumAreaCells, maximumWorkingMemoryBytes, retainedCandidates,
                    requiredCandidatePreparations, optionalCandidatePreparations, spawnCandidateAttempts,
                    requiredJointStates, requiredAreaStates, optionalAdditionAttempts, optionalJointStatesPerAttempt,
                    optionalAreaStatesPerAttempt, optionalJointStatesTotal, optionalAreaStatesTotal, extraAreaAttempts,
                    scatterAttempts, boundaryAttempts, requiredFloodVisits, optionalFloodVisits, finalValidationVisits,
                    maximumTerrainRepairVersions, maximumSupportDelta, supportFadeDistance,
                    competitiveGrowthOperations);
        }
    }
}
