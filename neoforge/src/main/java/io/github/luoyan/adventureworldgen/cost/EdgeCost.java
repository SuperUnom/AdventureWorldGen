package io.github.luoyan.adventureworldgen.cost;

/** Costs for both directions of one canonical physical segment. */
public record EdgeCost(State state, long forwardMicros, long reverseMicros) {
    public enum State { READY, BLOCKED }
    public static final EdgeCost BLOCKED = new EdgeCost(State.BLOCKED, Long.MAX_VALUE, Long.MAX_VALUE);
    public boolean passable() { return state == State.READY; }
}
