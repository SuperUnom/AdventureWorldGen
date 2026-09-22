package io.github.luoyan.adventureworldgen.plan;

/** Algorithmic limits, serialized into the canonical input identity; scheduling is separate. */
public record PlanningPolicy(int tileSide,int environmentStep,int candidateStep,int finalStep,
                             double preferredGrade,int supportReach,int boardwalkStep,double climateTranslationFraction,
                             java.util.List<Double> temperatureBiases,java.util.List<Double> humidityBiases,
                             double roadTravelWeight,double foundationWeight,double bridgeWeight,int windingRadius,int minimumLoopPerimeter,int minimumLoopSaving,RoadPolicy roads) {
    public static final PlanningPolicy CURRENT=new PlanningPolicy(256,32,16,4,.35,12,4,.35,
            java.util.List.of(0.,-.8,.8),java.util.List.of(0.,-.18,.18),.25,.15,1,192,128,32,
            new RoadPolicy(4,12,4,16_384,8_192,2,128,32,16_384,65_536,8_000_000,2_000_000,768,32,2.0));
    /** Per destination; nested construction and the single deferred retry share this allowance. */
    public record RoadPolicy(int attachments,int candidates,int validations,int coarseExpansions,
                             int repairExpansions,int repairs,int repairSide,int junctionRadius,
                             int routeLength,int candidateLength,long samples,long visits,int corridorPad,int coarseStep,double heuristicWeight) {
        public RoadPolicy {
            if(attachments<1||candidates<1||validations<1||coarseExpansions<1||repairExpansions<1||repairs<0
                    ||repairSide<8||junctionRadius<1||routeLength<1||candidateLength<routeLength||samples<1||visits<1||corridorPad<1||coarseStep<4||!Double.isFinite(heuristicWeight)||heuristicWeight<1)
                throw new IllegalArgumentException("invalid road policy");
        }
    }
    public PlanningPolicy {
        temperatureBiases=java.util.List.copyOf(temperatureBiases);humidityBiases=java.util.List.copyOf(humidityBiases);
        java.util.Objects.requireNonNull(roads);
    }
}
