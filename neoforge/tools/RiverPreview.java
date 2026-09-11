import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/**
 * Deterministic geometry overview and block-scale water preview, not an in-game screenshot.
 *
 * <p>Args: {@code <seed> <output.png> [before]}. {@code before} draws the nominal channel width
 * instead of the morphology-adjusted one, which is what the pre-r19 comparison looked like.
 *
 * <p>The block-scale panel used to compare river-bed <em>materials</em> through
 * {@code BiomeAdapter.surface}. That API was removed on purpose (P5.2): surface and river-bed
 * materials now come from the vanilla surface pipeline, which needs the game's registries and block
 * states and cannot be evaluated by a standalone preview program. The panel shades water by depth
 * instead - re-inventing a material source here would quietly become a second, divergent rule.
 */
public class RiverPreview {
    /** java.awt.Color rejects anything outside 0..255, so clamp instead of throwing mid-render. */
    private static int channel(double value) {
        return (int)StrictMath.max(0, StrictMath.min(255, value));
    }

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
                // Depth-shaded water column: deeper water is darker. The bed material itself is not
                // available here - see the class comment.
                double depth = StrictMath.max(0, sample.waterSurface() - sample.groundSurface());
                double shade = 1.35 - StrictMath.min(1, depth / 6.0) * 0.55;
                color = new Color(channel(92 * shade), channel(150 * shade), channel(196 * shade));
            } else {
                double shade = Math.max(0.75, Math.min(1.12, 1 + (sample.groundSurface() - 100) / 180));
                color = new Color((int)(181 * shade), (int)(196 * shade), (int)(150 * shade));
            }
            image.setRGB(820 + px, 100 + pz, color.getRGB());
        }
        g.setColor(new Color(28, 49, 53)); g.setFont(new Font("SansSerif", Font.PLAIN, 17));
        g.drawString("600 x 660 blocks | depth-shaded water", 820, 78);
        g.drawString("Geometry preview; bed materials come from the vanilla surface pipeline", 820, 790);
        g.dispose();
        Path output = Path.of(args[1]); Files.createDirectories(output.toAbsolutePath().getParent());
        ImageIO.write(image, "png", output.toFile());
        System.out.println("channels=" + network.channels().size() + " preview=" + output);
    }
}
