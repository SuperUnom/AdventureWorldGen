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
    public String stage() { return stage; }
    public Map<String, String> diagnostics() { return diagnostics; }
}
