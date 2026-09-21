package io.github.luoyan.adventureworldgen.worldgen.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

/** Execution settings, independent of planner inputs. Margin fits the native reference envelope. */
public record TerrainSettings(Mode mode, int margin) {
    public static final TerrainSettings NATIVE = new TerrainSettings(Mode.NATIVE, 12);
    public static final Codec<TerrainSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            StringRepresentable.fromEnum(Mode::values).fieldOf("mode").forGetter(TerrainSettings::mode),
            Codec.intRange(0, 12).optionalFieldOf("margin", 12).forGetter(TerrainSettings::margin)
    ).apply(i, TerrainSettings::new));

    public TerrainSettings {
        if (mode == null || margin < 0 || margin > 12) throw new IllegalArgumentException("invalid terrain settings");
    }

    public enum Mode implements StringRepresentable {
        NATIVE, NONE, FILL, FLATTEN;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
}
