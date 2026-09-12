package io.github.luoyan.adventureworldgen.plan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every random-domain literal in production code is registered.
 *
 * <p>The point is that the random surface cannot grow by accident. A new stage or noise domain is
 * one string away, and nothing else in the build would notice: the plan's {@code random_keys} array
 * is historical metadata, it is never read back and it does not name these values. This test scans
 * the production sources, extracts the domain argument of each random call, and requires it to be
 * registered in {@link RandomDomains}.
 *
 * <p>It deliberately covers all three shapes the code uses - {@code DeterministicRandom.seed},
 * {@code DeterministicRandom.sample} and the noise constructors - because scanning only one of them
 * would leave the other two free to drift.
 */
class RandomDomainsTest {
    /** Call shapes whose first string argument is a domain name. */
    private static final List<String> RANDOM_CALLS = List.of(
            "DeterministicRandom.seed(",
            "DeterministicRandom.sample(",
            "DeterministicRandom.mix(",
            "new GradientNoise(",
            "new ValueNoise(",
            "new ContinuousDomainWarp(");

    @Test
    void everyRandomDomainLiteralInProductionIsRegistered() throws IOException {
        Path root = sourceRoot();
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("RandomDomains.java")) continue;
                String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String call : RANDOM_CALLS) {
                    for (String arguments : argumentLists(source, call)) {
                        String first = firstLiteral(arguments);
                        if (first != null) found.add(first);
                    }
                }
            }
        }
        assertFalse(found.isEmpty(), "the scanner found no random call at all, which cannot be right");
        List<String> unregistered = found.stream().filter(name -> !RandomDomains.covers(name)).toList();
        assertTrue(unregistered.isEmpty(), () ->
                "these random-domain names are used in production but not registered in RandomDomains; "
                        + "add them (keeping the value byte-for-byte) so a domain cannot appear unnoticed:\n  "
                        + String.join("\n  ", unregistered));
    }

    @Test
    void theInventoryIsWellFormed() {
        // Names may legitimately repeat across the three roles - a stage and a salt are different
        // arguments of different calls - but a duplicate inside one role is a mistake.
        assertDistinct(RandomDomains.stageDomains(), "stage domains");
        assertDistinct(RandomDomains.seedSalts(), "seed salts");
        assertDistinct(RandomDomains.noiseDomains(), "noise domains");
        for (String name : RandomDomains.all()) {
            assertFalse(name.isBlank(), "a blank domain name is never valid");
            assertFalse(name.startsWith(" "), "domain names are not padded: " + name);
        }
        // Prefix matching is only used for labels built as prefix + component, and those end in '/'.
        assertTrue(RandomDomains.covers("ftf/coast/x/0"), "a derived noise label resolves through its prefix");
        assertTrue(RandomDomains.covers("growth/3"));
        assertFalse(RandomDomains.covers("ftf/coast/x"), "prefix matching does not skip the separator");
        assertFalse(RandomDomains.covers("some-new-stage"));
    }

    @Test
    void thePlanRandomKeysArrayIsDescriptiveOnly() throws IOException {
        // The plan-v2 array must not be mistaken for this inventory: it is metadata written for the
        // wire, its names are not the ones used above, and no decoder reads it.
        Path codec = sourceRoot().getParent().getParent().resolve("java")
                .resolve("io/github/luoyan/adventureworldgen/persistence/PlanV2Codec.java");
        Path repository = Path.of(System.getProperty("adventureworldgen.sourceRoot")).toAbsolutePath()
                .resolve("persistence/PlanV2Codec.java");
        Path file = Files.isRegularFile(repository) ? repository : codec;
        assertTrue(Files.isRegularFile(file), () -> "cannot locate PlanV2Codec at " + repository);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(source.contains("writeHistoricalRandomKeys"), "the metadata must stay in the encoder");
        assertTrue(source.contains("not read back"),
                "the encoder must say that random_keys is not read back");
        // decode() must not consult the array at all.
        String decode = source.substring(source.indexOf("public PlanSnapshot decode("));
        assertFalse(decode.contains("random_keys"),
                "decode() must ignore random_keys; reading it would turn metadata into a contract");
    }

    private static void assertDistinct(List<String> names, String role) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (String name : names) if (!seen.add(name)) duplicates.add(name);
        assertTrue(duplicates.isEmpty(), () -> role + " lists a name twice: " + duplicates);
    }

    private static Path sourceRoot() {
        String configured = System.getProperty("adventureworldgen.sourceRoot");
        assertTrue(configured != null && !configured.isBlank(),
                "missing adventureworldgen.sourceRoot; the test task sets it");
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root), () -> "no source tree at " + root);
        return root;
    }

    /** The argument text of every call to {@code call}, with balanced parentheses. */
    private static List<String> argumentLists(String source, String call) {
        List<String> lists = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = source.indexOf(call, from);
            if (at < 0) break;
            from = at + call.length();
            int depth = 1, i = from;
            boolean inString = false;
            while (i < source.length() && depth > 0) {
                char c = source.charAt(i);
                if (inString) {
                    if (c == '\\') i++;
                    else if (c == '"') inString = false;
                } else if (c == '"') {
                    inString = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                i++;
            }
            lists.add(source.substring(from, Math.min(i, source.length())));
        }
        return lists;
    }

    /** The first string literal in the argument list, or null when it is not a literal. */
    private static String firstLiteral(String arguments) {
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '"') {
                StringBuilder text = new StringBuilder();
                int j = i + 1;
                while (j < arguments.length() && arguments.charAt(j) != '"') {
                    if (arguments.charAt(j) == '\\') j++;
                    text.append(arguments.charAt(j));
                    j++;
                }
                return text.toString();
            }
            // Stop at the first nested call or operator: a first argument that is an expression is
            // not a literal domain and is checked by whatever that expression resolves to.
            if (c == '(' || c == '+' || c == ',' && false) return null;
        }
        return null;
    }

    /** Removes comments and string-literal contents is not needed; comments are enough to strip. */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"') {
                out.append(c);
                i++;
                while (i < source.length()) {
                    char inner = source.charAt(i);
                    out.append(inner);
                    i++;
                    if (inner == '\\' && i < source.length()) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (inner == '"') {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i = Math.min(i + 2, source.length());
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
