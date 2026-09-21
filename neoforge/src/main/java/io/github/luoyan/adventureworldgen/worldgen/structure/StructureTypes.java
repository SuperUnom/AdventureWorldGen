package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class StructureTypes {
    private static final DeferredRegister<StructureType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, "adventureworldgen");
    private static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, "adventureworldgen");
    public static final DeferredHolder<StructureType<?>, StructureType<TemplateStructure>> TEMPLATE =
            TYPES.register("template", () -> () -> TemplateStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> TEMPLATE_PIECE =
            PIECES.register("template", () -> TemplateStructure.Piece::new);
    private StructureTypes() {}
    public static void register(IEventBus bus) { TYPES.register(bus); PIECES.register(bus); }
}
