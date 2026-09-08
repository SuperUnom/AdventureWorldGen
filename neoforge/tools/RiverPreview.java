import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.ContentId;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.worldgen.VanillaRiverBiomeAdapter;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Deterministic geometry overview and block-scale bed preview, not an in-game screenshot. */
public class RiverPreview {
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        boolean before = args.length > 2 && args[2].equals("before");
        var coast = new CoastGenerator(PlannerProfile.V2).generate(seed, 3000, 300);
        var island = new IslandMacroTerrain(coast.coastline(), new RegionTerrain(seed, PlannerProfile.V2), seed,
                64, coast.landBand(), coast.seaBand(), "preview");
        var network = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(seed, 3000, 64, coast.coastline(), island);
        var terrain = new HydrologyTerrain(island, network);
        var morphology = new RiverMorphology(network);
        var sediment = new VanillaRiverBiomeAdapter(new ContentId("minecraft:river"));
        var image = new BufferedImage(1440, 820, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new Color(239, 243, 240)); g.fillRect(0, 0, 1440, 820);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif", Font.BOLD, 22)); g.setColor(new Color(28, 49, 53));
        g.drawString((before ? "BEFORE" : "AFTER") + "  |  River geometry  |  Seed " + seed, 24, 32);
        g.setColor(new Color(192, 222, 230)); g.fillRect(20, 60, 760, 760);
        var outline = new Path2D.Double();
        boolean first = true;
        for (var p : coast.coastline().vertices()) {
            double x = 400 + p.x() * 0.12, z = 440 + p.z() * 0.12;
            if (first) { outline.moveTo(x, z); first = false; } else outline.lineTo(x, z);
        }
        outline.closePath(); g.setColor(new Color(203, 213, 184)); g.fill(outline);
        for (var ch : network.channels()) {
            g.setColor(new Color(40, 135, 173));
            for (int i = 1; i < ch.points().size(); i++) {
                var a = ch.points().get(i - 1); var b = ch.points().get(i);
                double width = before ? ch.shape().bedWidth() : morphology.bedRadius(ch,
                        ch.cumulativeLengths().get(i) / ch.length(), b.x(), b.z(), b.x(), b.z());
                g.setStroke(new BasicStroke((float)Math.max(1, width * 0.24), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(400 + a.x() * 0.12, 440 + a.z() * 0.12, 400 + b.x() * 0.12, 440 + b.z() * 0.12));
            }
            if (ch.order() == 0) System.out.printf("%s points=%d width=%d sinuosity=%.3f%n", ch.id(), ch.points().size(),
                    ch.shape().bedWidth() * 2, ch.length() / ch.points().getFirst().distance(ch.points().getLast()));
        }
        var trunk = network.channels().getFirst();
        var center = trunk.points().get(trunk.points().size() * 2 / 3);
        g.setColor(new Color(191, 94, 49)); g.setStroke(new BasicStroke(1.5f));
        g.draw(new Rectangle2D.Double(400 + (center.x() - 300) * 0.12, 440 + (center.z() - 330) * 0.12, 72, 79.2));
        for (int px = 0; px < 600; px++) for (int pz = 0; pz < 660; pz++) {
            int x = (int)Math.floor(center.x()) + px - 300, z = (int)Math.floor(center.z()) + pz - 330;
            var sample = terrain.sample(x + 0.5, z + 0.5);
            Color color;
            if (sample.waterKind() == WaterKind.OCEAN) color = new Color(146, 197, 215);
            else if (sample.wet()) {
                String top = before ? "minecraft:gravel" : sediment.surface(sample, seed, x, z).top().value();
                Color bed = switch (top) {
                    case "minecraft:sand" -> new Color(224, 204, 140);
                    case "minecraft:dirt" -> new Color(133, 106, 74);
                    case "minecraft:clay" -> new Color(167, 180, 199);
                    case "minecraft:stone" -> new Color(102, 110, 107);
                    default -> new Color(150, 146, 136);
                };
                color = new Color((int)(bed.getRed() * 0.65 + 20), (int)(bed.getGreen() * 0.65 + 57), (int)(bed.getBlue() * 0.65 + 73));
            } else {
                double shade = Math.max(0.75, Math.min(1.12, 1 + (sample.groundSurface() - 100) / 180));
                color = new Color((int)(181 * shade), (int)(196 * shade), (int)(150 * shade));
            }
            image.setRGB(820 + px, 100 + pz, color.getRGB());
        }
        g.setColor(new Color(28, 49, 53)); g.setFont(new Font("SansSerif", Font.PLAIN, 17));
        g.drawString("600 x 660 blocks | water and bed materials", 820, 78);
        g.drawString("Geometry preview; no erosion, vegetation or structures", 820, 790);
        g.dispose();
        Path output = Path.of(args[1]); Files.createDirectories(output.toAbsolutePath().getParent());
        ImageIO.write(image, "png", output.toFile());
        System.out.println("channels=" + network.channels().size() + " preview=" + output);
    }
}
