package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.config.RoadSettings;
import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Executes frozen columns in the current chunk only. No loading callbacks or neighbour writes. */
public final class RoadWorldgen {
    private RoadWorldgen() {}
    public record Palette(BlockState surface,BlockState bridge,BlockState foundation) {}
    public static Palette palette(RoadSettings settings) {
        return new Palette(material(settings.surface()),material(settings.bridge()),material(settings.foundation()));
    }
    private static BlockState material(String id) {
        var key=ResourceLocation.parse(id);
        if(!BuiltInRegistries.BLOCK.containsKey(key))throw new IllegalArgumentException("missing road material "+id);
        Block block=BuiltInRegistries.BLOCK.get(key);var state=block.defaultBlockState();
        if(!state.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO)
                ||!state.getFluidState().isEmpty()||state.hasBlockEntity())throw new IllegalArgumentException("road material must be a solid cube without fluid/block entity: "+id);
        return state;
    }
    public static void column(NoiseColumn column,RoadPlan.Column road,Palette palette,AdventurePlanView plan) {
        if(road==null)return;
        for(int y=road.bottomY();y<=road.clearTopY();y++)column.setBlock(y,y==road.deckY()?top(road,plan,palette):state(road,y,palette));
    }
    public static void surface(ChunkAccess chunk,AdventurePlanView plan,Palette palette) {
        var pos=new BlockPos.MutableBlockPos();
        for(var road:plan.roadsInChunk(chunk.getPos().x,chunk.getPos().z)) {
            for(int y=road.bottomY();y<=road.clearTopY();y++)chunk.setBlockState(pos.set(road.x(),y,road.z()),state(road,y,palette),false);
            chunk.setBlockState(pos.set(road.x(),road.deckY(),road.z()),top(road,plan,palette),false);
        }
    }
    private static BlockState top(RoadPlan.Column road,AdventurePlanView plan,Palette palette) {
        for(var direction:Direction.Plane.HORIZONTAL) {
            var lower=plan.roadAt(road.x()-direction.getStepX(),road.z()-direction.getStepZ());
            if(lower!=null&&lower.deckY()==road.deckY()-1)
                return (road.bridge()?Blocks.OAK_STAIRS:Blocks.COBBLESTONE_STAIRS).defaultBlockState().setValue(StairBlock.FACING,direction);
        }
        return road.bridge()?palette.bridge():road.shoulder()?Blocks.COARSE_DIRT.defaultBlockState():palette.surface();
    }
    private static BlockState state(RoadPlan.Column road,int y,Palette palette) {
        if(y>road.deckY())return Blocks.AIR.defaultBlockState();
        if(road.bridge())return palette.bridge();
        return y==road.deckY()?(road.shoulder()?Blocks.COARSE_DIRT.defaultBlockState():palette.surface()):palette.foundation();
    }
    /** Conservative horizontal exclusion also protects structure foundations and underground pieces. */
    public static boolean intersects(AdventurePlanView plan,BoundingBox box,int margin) {
        for(int cx=(box.minX()-margin)>>4;cx<=(box.maxX()+margin)>>4;cx++)for(int cz=(box.minZ()-margin)>>4;cz<=(box.maxZ()+margin)>>4;cz++)
            for(var road:plan.roadsInChunk(cx,cz))if(road.x()>=box.minX()-margin&&road.x()<=box.maxX()+margin
                    &&road.z()>=box.minZ()-margin&&road.z()<=box.maxZ()+margin)return true;
        return false;
    }
}
