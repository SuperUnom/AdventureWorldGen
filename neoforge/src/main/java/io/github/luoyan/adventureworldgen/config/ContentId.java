package io.github.luoyan.adventureworldgen.config;

import java.util.Objects;
import java.util.regex.Pattern;

/** A fully-qualified Minecraft content identifier, without registry lookup. */
public record ContentId(String value) implements Comparable<ContentId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    public ContentId {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1
                || !NAMESPACE.matcher(value.substring(0, separator)).matches()
                || !PATH.matcher(value.substring(separator + 1)).matches()) {
            throw new IllegalArgumentException("content ID must be a full namespace:path identifier");
        }
    }

    public String namespace() {
        return value.substring(0, value.indexOf(':'));
    }

    public String path() {
        return value.substring(value.indexOf(':') + 1);
    }

    @Override
    public int compareTo(ContentId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
