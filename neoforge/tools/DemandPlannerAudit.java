import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.erosion.*;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.api.*;
import java.nio.file.*;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Full frozen environment and biome pipeline; structures use a bounded test footprint, not Minecraft NBT. */
public class DemandPlannerAudit {
 public static void main(String[] args)throws Exception {
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  Path out=Path.of(args[0]);Files.createDirectories(out);
  if(args.length>1&&(args[1].equals("--preview")||args[1].equals("--render"))) {
   long seed=Long.parseLong(args[2]);
   var plan=new io.github.luoyan.adventureworldgen.persistence.PlanV2Codec().decode(Files.readAllBytes(out.resolve(seed+"-plan.json")),new ContentId("adventureworldgen:default"),"audit",config);
   renderEnvironment(out,seed,config,plan::terrainAt,plan.climate());if(args[1].equals("--render")){validateAndReport(out,seed,config,plan);renderBiomes(out,seed,config,plan);}return;
  }
  for(int a=1;a<args.length;a++) {
   long seed=Long.parseLong(args[a]);long start=System.nanoTime();System.out.println("SEED "+seed+" environment");
   var profile=PlannerProfile.V2;double radius=config.world().radius();
   var coast=new CoastGenerator(profile).generate(seed,radius,Math.min(256,radius/10)+32);
   var capacity=TerrainCapacityPlan.reserve(seed,config,coast.coastline(),coast.landBand());
   var island=new IslandMacroTerrain(coast.coastline(),new RegionTerrain(seed,profile,capacity,config.world().terrain(),config),seed,64,coast.landBand(),coast.seaBand(),"terrain-r21");
   int extent=(int)Math.ceil((radius+256)/8)*8,size=extent*2/8+1;
   var erosion=new ErosionGenerator(profile,HydrologyProfile.FINITE_CONTINENT).generate(seed,island,-extent,-extent,8,size,size);
   var eroded=new ErodedTerrain(island,erosion,"erosion-v1");
   var rivers=new HydrologyGenerator(profile,HydrologyProfile.FINITE_CONTINENT).generate(seed,radius,64,coast.coastline(),eroded);
   var terrain=new TerrainMorphology(new HydrologyTerrain(eroded,rivers));
   var climate=new ClimatePlan(seed,config,terrain);
   renderEnvironment(out,seed,config,terrain,climate);
   System.out.println("MAPS "+out.resolve(seed+"-temperature.png")+" "+out.resolve(seed+"-terrain.png"));
   var levels=new JointPlanner.LevelConstraint(){public boolean accepts(int l,int x,int z){return true;}
    public double penalty(int l,int x,int z){return io.github.luoyan.adventureworldgen.cost.AdventurePreference.penalty(l,10*Math.hypot(x,z)/radius);}};
   var joint=new JointPlanner(profile).plan(seed,config,terrain,(d,x,y,z,s)->new AdventurePlanView.PlannedStructure(d.instanceId(),d.structureId(),x,y,z,"north",
    java.util.List.of(new AdventurePlanView.PlannedPiece(d.instanceId()+"/0",x-10,y,z-10,x+10,y+14,z+10,new byte[]{1}))),levels);
   System.out.println("SEED "+seed+" filler and validation");
   var plan=new GeneratedAdventurePlan(seed,config,coast.coastline(),rivers,64,coast.landBand(),coast.seaBand(),"terrain-r21",joint.spawn(),joint.patches(),joint.structures(),
    GeneratedAdventurePlan.PlanDiagnostics.basic(coast.coastline(),rivers),erosion,capacity);
   validateAndReport(out,seed,config,plan);
   renderBiomes(out,seed,config,plan);
   Files.writeString(out.resolve(seed+"-summary.txt"),"seed="+seed+"\ntarget_ratios="+Arrays.toString(plan.climate().targetRatios())+"\nactual_ratios="+Arrays.toString(plan.climate().actualRatios())+"\nfiller_seeds="+plan.fillerSeedCount()+"\noperations="+joint.operationCount()+"\nseconds="+(System.nanoTime()-start)/1e9+"\nsupply="+plan.climate().supply()+"\n");
   // Exercise the production codec, without publishing an artificial test footprint to a game world.
   var codec=new io.github.luoyan.adventureworldgen.persistence.PlanV2Codec();
   byte[] encoded=codec.encode(new ContentId("adventureworldgen:default"),"audit",plan);
   Files.write(out.resolve(seed+"-plan.json"),encoded);
   var loadProgress=PlanningProgress.begin("audit-reload");long loadStart=System.nanoTime();
   var replay=codec.decode(encoded,new ContentId("adventureworldgen:default"),"audit",config);
   if(loadProgress.snapshot().stage()!=PlanningProgress.Stage.CACHE)throw new AssertionError("reload replanned "+loadProgress.snapshot().stage());
   System.out.println("RELOAD seed="+seed+" milliseconds="+(System.nanoTime()-loadStart)/1e6+" stage="+loadProgress.snapshot().stage());PlanningProgress.clear();
   for(int z=-3000;z<=3000;z+=71)for(int x=-3000;x<=3000;x+=71)
    if(!plan.biomeAt(x,64,z).equals(replay.biomeAt(x,64,z)))throw new AssertionError("replay mismatch");
   System.out.println("PASS seed="+seed+" patches="+joint.patches().size()+" operations="+joint.operationCount()+" seconds="+(System.nanoTime()-start)/1e9);
  }
 }
 static void validateAndReport(Path out,long seed,AdventureWorldConfig config,GeneratedAdventurePlan plan)throws Exception {
   StringBuilder report=new StringBuilder("patch\tbiome\tminimum\ttarget\tarea\teffective_area\tcomponents\tlevel\tdistance\n");
   for(var d:new RequirementExpander().expandMinimum(config).patches()) {
    var p=plan.biomePatches().stream().filter(v->v.patchId().equals(d.patchId())).findFirst().orElseThrow();
    long effective=plan.effectiveArea(p);int components=components(p);
    if(effective<d.area().min()||p.area()>d.area().max()||components!=1)throw new AssertionError("Invalid "+p.patchId()+" effective="+effective+" components="+components);
    report.append(p.patchId()+"\t"+p.biomeId()+"\t"+d.area().min()+"\t"+d.area().target()+"\t"+p.area()+"\t"+effective+"\t"+components+"\t"+p.adventureLevel()+"\t"+Math.hypot(p.anchorX(),p.anchorZ())+"\n");
   }
   Files.writeString(out.resolve(seed+"-areas.tsv"),report);
 }
 static int components(GeneratedAdventurePlan.PlannedBiomePatch p) {
  Set<Long> remaining=new HashSet<>();for(long c:p.mask().cells())remaining.add(c);int n=0;
  while(!remaining.isEmpty()) {n++;var q=new ArrayDeque<Long>();long first=remaining.iterator().next();remaining.remove(first);q.add(first);
   while(!q.isEmpty()){long c=q.remove();int x=CellMask.x(c),z=CellMask.z(c);for(int[] d:new int[][]{{4,0},{-4,0},{0,4},{0,-4}}){long next=CellMask.key(x+d[0],z+d[1]);if(remaining.remove(next))q.add(next);}}
  }return n;
 }
 static BufferedImage frame(String title,long seed) {
  var im=new BufferedImage(840,960,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();g.setColor(new Color(0x152331));g.fillRect(0,0,840,960);
  g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,22));g.drawString(title,20,30);g.setFont(new Font("SansSerif",0,15));g.drawString("Seed "+seed+" | 6200 x 6200 blocks | + spawn",20,55);g.dispose();return im;
 }
 static void renderEnvironment(Path out,long seed,AdventureWorldConfig c,MacroTerrain terrain,ClimatePlan climate)throws Exception {
  var temp=frame("Demand-calibrated temperature bands",seed);var land=frame("Frozen terrain, elevation and water",seed);var bands=frame("Temperature classes | continuous field thresholds",seed);
  var humidity=frame("Humidity | dry, medium, wet",seed);
  for(int z=0;z<780;z++)for(int x=0;x<780;x++) {
   int wx=-3100+x*6200/780,wz=-3100+z*6200/780;var s=terrain.sample(wx,wz);int tc,lc;
   if(s.wet()){tc=lc=s.waterKind()==WaterKind.OCEAN?0x193C57:0x4E9BBC;}
   else {
    int category=switch(climate.typeAt(wx,wz,s)){case VERY_COLD->0xE5F1F4;case COLD->0x7ABBDC;case MEDIUM->0x89B872;case HOT->0xDFAC62;};
    bands.setRGB(x+30,z+80,category);
    tc=heatColor(climate.valueAt(wx,wz,s));
    int base=switch(s.terrainTemplate()){case "plains"->0x8AB56E;case "hills"->0xB9B879;case "plateau"->0xBB946D;default->0xA2A3B3;};
    double shade=Math.clamp(.7+(s.groundSurface()-64)/230,.65,1.15);lc=shade(base,shade);
   }
   int hc=s.wet()?tc:switch(climate.humidity().typeAt(wx,wz,s)){case DRY->0xD8B875;case MEDIUM->0x99B88A;case WET->0x418C94;};
   humidity.setRGB(x+30,z+80,hc);
   if(s.wet())bands.setRGB(x+30,z+80,tc);temp.setRGB(x+30,z+80,tc);land.setRGB(x+30,z+80,lc);
  }
  label(temp,"Continuous temperature: blue (0) -> green (5) -> amber (10)", "Target VC/C/M/H: "+percent(climate.targetRatios())+" | Actual: "+percent(climate.actualRatios()));
  label(land,"Green: plains   Olive: hills   Brown: plateau   Gray: mountains", "Elevation affects shading; blue marks real coast, rivers and lakes.");
  label(bands,"VERY COLD: snow white   COLD: blue   MILD: green   HOT: amber", "Classification used for configuration; biome scores use continuous temperature.");
  label(humidity,"Sand: dry   Green: medium   Teal: wet   Blue: water", "Actual D/M/W: "+percent(climate.humidity().actualRatios()));
  ImageIO.write(humidity,"png",out.resolve(seed+"-humidity.png").toFile());
  ImageIO.write(bands,"png",out.resolve(seed+"-temperature-classes.png").toFile());
  ImageIO.write(temp,"png",out.resolve(seed+"-temperature.png").toFile());ImageIO.write(land,"png",out.resolve(seed+"-terrain.png").toFile());
 }
 static void renderBiomes(Path out,long seed,AdventureWorldConfig c,GeneratedAdventurePlan plan)throws Exception {
  var base=frame("Biome layout | required seeds + competitive growth",seed);
  var im=new BufferedImage(1140,960,BufferedImage.TYPE_INT_RGB);var bg=im.createGraphics();bg.setColor(new Color(0x152331));bg.fillRect(0,0,1140,960);bg.drawImage(base,0,0,null);bg.dispose();
  for(int z=0;z<780;z++)for(int x=0;x<780;x++){int wx=-3100+x*6200/780,wz=-3100+z*6200/780;var id=plan.biomeAt(wx,64,wz);im.setRGB(x+30,z+80,color(id,c));}
  var g=im.createGraphics();g.setColor(Color.WHITE);
  for(var p:plan.biomePatches()){if(p.patchId().startsWith("filler/"))continue;int px=30+(p.anchorX()+3100)*780/6200,pz=80+(p.anchorZ()+3100)*780/6200;g.drawOval(px-3,pz-3,6,6);}
  g.setColor(new Color(0xFF8A40));
  for(var structure:plan.structures()){int px=30+(structure.originX()+3100)*780/6200,pz=80+(structure.originZ()+3100)*780/6200;g.drawRect(px-4,pz-4,8,8);}
  int row=0;g.setFont(new Font("SansSerif",0,13));
  for(var id:legendIds(c)){g.setColor(new Color(color(id,c)));g.fillRect(838,85+row*25,15,15);g.setColor(Color.WHITE);g.drawString(id.value().replace("minecraft:",""),862,97+row*25);row++;}
  g.dispose();label(im,"White dots: required biome seeds | Orange squares: structures placed afterward", "Generated from current code and frozen terrain; not an in-game screenshot.");ImageIO.write(im,"png",out.resolve(seed+"-biomes.png").toFile());
  var legend=new StringBuilder();for(var id:legendIds(c))legend.append(id+" #"+String.format("%06x",color(id,c)&0xffffff)+"\n");Files.writeString(out.resolve(seed+"-legend.txt"),legend);
 }
 static java.util.List<ContentId> legendIds(AdventureWorldConfig c){var ids=new TreeSet<ContentId>(c.biomes().filler());c.biomes().required().forEach(r->ids.add(r.id()));return java.util.List.copyOf(ids);}
 static int color(ContentId id,AdventureWorldConfig c) {
  String n=id.value();if(n.endsWith("ocean"))return 0x193C57;if(n.endsWith("river"))return 0x4E9BBC;
  return switch(n.replace("minecraft:","")) {
   case "ice_spikes"->0x80D1EB;case "sunflower_plains"->0xD3CA58;case "plains"->0x8DB765;case "forest"->0x367E44;case "flower_forest"->0x8ABF9C;
   case "birch_forest"->0x8CA84C;case "old_growth_birch_forest"->0xB1BD76;case "dark_forest"->0x274D3A;
   case "taiga"->0x508B80;case "old_growth_pine_taiga"->0x6B9585;case "old_growth_spruce_taiga"->0x3C666B;
   case "snowy_taiga"->0xB3D6CE;case "snowy_plains"->0xDAE4D9;case "jungle"->0x197756;
   case "sparse_jungle"->0x49A96D;case "bamboo_jungle"->0x8EAD31;case "mangrove_swamp"->0x326C65;
   case "swamp"->0x657653;case "meadow"->0xBDC786;case "cherry_grove"->0xD7A0BB;
   case "grove"->0xB8C5CB;case "snowy_slopes"->0xD5D7E7;case "jagged_peaks"->0x98ABC3;
   case "frozen_peaks"->0xE5F1F4;case "stony_peaks"->0xA28D88;case "windswept_forest"->0x748664;
   case "windswept_hills"->0x979266;case "windswept_gravelly_hills"->0x929CA0;case "desert"->0xE1C183;
   case "savanna"->0xB0AA50;case "savanna_plateau"->0xB9995F;case "windswept_savanna"->0xC2AD79;
   default->Color.HSBtoRGB((float)((PlacementIndex.mix(n.hashCode())>>>11)*0x1.0p-53),.43f,.88f)&0xffffff;
  };
 }
 static int heatColor(double value) {
  int[] colors={0x477BBC,0x7DBFC6,0xA8C783,0xD8BD6F,0xD7784F};
  double at=Math.clamp(value/2.5,0,3.999999);int i=(int)at;double f=at-i;
  int a=colors[i],b=colors[i+1];return (int)(((a>>16)&255)*(1-f)+((b>>16)&255)*f)<<16
   |(int)(((a>>8)&255)*(1-f)+((b>>8)&255)*f)<<8|(int)((a&255)*(1-f)+(b&255)*f);
 }
 static int shade(int rgb,double f){return Math.min(255,(int)(((rgb>>16)&255)*f))<<16|Math.min(255,(int)(((rgb>>8)&255)*f))<<8|Math.min(255,(int)((rgb&255)*f));}
 static String percent(double[] a){return Arrays.stream(a).mapToObj(v->String.format(java.util.Locale.ROOT,"%.1f",v*100)).collect(java.util.stream.Collectors.joining(" / "))+" %";}
 static void label(BufferedImage im,String first,String second){var g=im.createGraphics();g.setColor(Color.WHITE);g.drawLine(415,470,425,470);g.drawLine(420,465,420,475);g.setFont(new Font("SansSerif",0,14));g.drawString(first,20,895);g.drawString(second,20,922);g.dispose();}
}
