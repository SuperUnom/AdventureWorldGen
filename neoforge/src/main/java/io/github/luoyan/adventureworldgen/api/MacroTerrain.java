package io.github.luoyan.adventureworldgen.api;

/** Single authoritative continuous function used by every coarse and runtime sample. */
@FunctionalInterface
public interface MacroTerrain {
    MacroSample sample(double x, double z);
}
