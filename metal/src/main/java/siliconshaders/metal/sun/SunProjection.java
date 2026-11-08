package siliconshaders.metal.sun;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Fixed light basis and a nearby world-space anchor, including at the sun's zenith. */
public final class SunProjection {
    private static final double ANCHOR_STEP=2.0;
    private SunProjection() {}

    public static Matrix4f create(Vector3fc sun,double cameraX,double cameraY,double cameraZ,int size,float range) {
        // The sun moves in XY, so Z is perpendicular throughout the day. Switching up axes near noon rotates the grid.
        float depth=Math.max(128,2*range);
        Matrix4f light=new Matrix4f().setOrtho(-range,range,-range,range,0.1f,2*depth,true)
            .mul(new Matrix4f().lookAt(new Vector3f(sun).mul(depth),new Vector3f(),new Vector3f(0,0,1)));
        if(size>0) {
            // Rotate the light grid about a nearby fixed world-space cell, rather than the world origin.
            // Projecting the absolute camera position onto a rotating light basis makes its subtexel
            // phase sweep rapidly as the sun moves, even with a stationary camera far from (0,0,0).
            // Within this cell, world geometry stays fixed in the shadow map as the camera moves.
            light.translate(localOffset(cameraX),localOffset(cameraY),localOffset(cameraZ));
        }
        return light;
    }

    private static float localOffset(double position) {
        return (float)(position-(Math.floor(position/ANCHOR_STEP)+0.5)*ANCHOR_STEP);
    }
}
