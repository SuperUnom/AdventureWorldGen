package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.config.ContentId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Transactional plan-v2 storage; implementations publish only fully validated READY snapshots. */
public interface PlanRepository {
    Optional<ReadyPlan> loadReady(Path worldDirectory, ContentId profileId,
                                  String expectedInputHash) throws IOException;
    void publishAtomically(Path worldDirectory, ContentId profileId, byte[] canonicalPlan,
                           String inputHash) throws IOException;

    record ReadyPlan(byte[] canonicalPlan, String payloadHash, String inputHash) {
        public ReadyPlan { canonicalPlan = canonicalPlan.clone(); }
        @Override public byte[] canonicalPlan() { return canonicalPlan.clone(); }
    }
}
