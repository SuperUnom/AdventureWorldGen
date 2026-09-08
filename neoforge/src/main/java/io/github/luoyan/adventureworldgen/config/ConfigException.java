package io.github.luoyan.adventureworldgen.config;

import java.util.Objects;

public final class ConfigException extends RuntimeException {
    private final ConfigErrorCode code;
    private final String path;

    public ConfigException(ConfigErrorCode code, String path, String detail) {
        super(code + " at " + path + ": " + detail);
        this.code = Objects.requireNonNull(code, "code");
        this.path = Objects.requireNonNull(path, "path");
    }

    public ConfigErrorCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
