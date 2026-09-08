package io.github.luoyan.adventureworldgen.terrain;

/** Perlin.sample / NoiseUtil.gradCoord2D from FTF 43fd42a4 (MIT; see META-INF/NOTICE).
 * Keep upstream's eight gradients and CURVE3 interpolation. The general project noise
 * has normalized diagonal gradients, quintic interpolation and an extra gain; substituting
 * it inside nested upstream warps changes both their derivatives and their relief.
 */
final class TerraForgedNoise {
    private TerraForgedNoise() {}

    static double perlin(double x,double z,int seed) {
        int ix=(int)Math.floor(x),iz=(int)Math.floor(z);
        double dx=x-ix,dz=z-iz,u=dx*dx*(3-2*dx),v=dz*dz*(3-2*dz);
        double a=lerp(gradient(seed,ix,iz,dx,dz),gradient(seed,ix+1,iz,dx-1,dz),u);
        double b=lerp(gradient(seed,ix,iz+1,dx,dz-1),gradient(seed,ix+1,iz+1,dx-1,dz-1),u);
        return lerp(a,b,v);
    }

    private static double gradient(int seed,int x,int z,double dx,double dz) {
        int hash=seed^(1619*x)^(31337*z);
        hash=hash*hash*hash*60493;hash^=hash>>13;
        return switch(hash&7) {
            case 0 -> -dx-dz; case 1 -> dx-dz; case 2 -> -dx+dz; case 3 -> dx+dz;
            case 4 -> -dz; case 5 -> -dx; case 6 -> dz; default -> dx;
        };
    }
    private static double lerp(double a,double b,double t) {return a+(b-a)*t;}
}
