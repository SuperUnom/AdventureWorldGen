package io.github.luoyan.adventureworldgen.plan;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** A typed terminal planner error with deterministic, persistable diagnostics. */
public class PlanningFailure extends RuntimeException {
    public enum Code {
        CONFIG_CONFLICT, UNSUPPORTED_CONTENT, RESOURCE_LIMIT, SEARCH_BUDGET_EXHAUSTED,
        PRECISION_INSUFFICIENT, NO_SOLUTION_IN_DOMAIN, EXECUTION_FAILED
    }

    private final Code code;
    private final String stage;
    private final Map<String, String> diagnostics;

    public PlanningFailure(Code code, String stage, String message) {
        this(code, stage, message, Map.of(), null);
    }

    public PlanningFailure(Code code, String stage, String message, Map<String, ?> diagnostics) {
        this(code, stage, message, diagnostics, null);
    }

    /**
     * Builds a failure from the {@link FailureStage} vocabulary. The stored stage is still the
     * stable {@link FailureStage#id() id string}, so every existing reader sees the same text while
     * production call sites stop inventing their own literals.
     */
    public PlanningFailure(Code code, FailureStage stage, String message) {
        this(code, Objects.requireNonNull(stage, "stage").id(), message, Map.of(), null);
    }

    /** @see #PlanningFailure(Code, FailureStage, String) */
    public PlanningFailure(Code code, FailureStage stage, String message, Map<String, ?> diagnostics) {
        this(code, Objects.requireNonNull(stage, "stage").id(), message, diagnostics, null);
    }

    /** @see #PlanningFailure(Code, FailureStage, String) */
    public PlanningFailure(Code code, FailureStage stage, String message, Map<String, ?> diagnostics,
                           Throwable cause) {
        this(code, Objects.requireNonNull(stage, "stage").id(), message, diagnostics, cause);
    }

    public PlanningFailure(Code code, String stage, String message, Map<String, ?> diagnostics, Throwable cause) {
        super("[" + Objects.requireNonNull(code, "code") + "] "
                + Objects.requireNonNull(stage, "stage") + ": " + Objects.requireNonNull(message, "message")
                + (diagnostics.isEmpty() ? "" : " " + new TreeMap<>(diagnostics)), cause);
        this.code = code;
        this.stage = stage;
        TreeMap<String, String> normalized = new TreeMap<>();
        diagnostics.forEach((key, value) -> normalized.put(key, String.valueOf(value)));
        this.diagnostics = Collections.unmodifiableMap(normalized);
    }

    public Code code() { return code; }

    /**
     * The failure stage as the free-text string it has always been. Kept for wire and log
     * compatibility: existing diagnostics, dashboards and stored reports keep reading exactly what
     * they read before. Prefer {@link #failureStage()} for new logic.
     */
    public String stage() { return stage; }

    /**
     * The stage as a member of the {@link FailureStage} vocabulary, with an explicit
     * {@link FailureStage#UNKNOWN} fallback for a string this build does not recognise. Use this
     * instead of matching on {@link #stage()} or on the message: doing so keeps classification,
     * location and wording separate.
     */
    public FailureStage failureStage() { return FailureStage.of(stage); }

    public Map<String, String> diagnostics() { return diagnostics; }

    /** The progress stage this failure belongs to, when it happened inside the progress bar. */
    public java.util.Optional<PlanningStage> progressStage() { return failureStage().progressStage(); }
}
