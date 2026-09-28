package com.player2.playerengine.seam;

import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.List;
import java.util.Map;

/**
 * Where a job may walk (§4.2): in a job, {@code goto} and {@code follow} targets must lie inside the
 * job's region, a sphere of {@link #RADIUS} blocks around the initiator at job start. Outside a job
 * there is no region, and a lone command walks where it is told.
 */
public final class MotionBounds {
    public static final int RADIUS = 48;

    private MotionBounds() {
    }

    /** A job's region: its dimension and the initiator's position when the job started. */
    public record Region(String dimension, AreaSpec.Pos anchor, int radius) {
        public static Region around(String dimension, AreaSpec.Pos anchor) {
            return new Region(dimension, anchor, RADIUS);
        }

        public boolean contains(String dim, AreaSpec.Pos p) {
            long dx = p.x() - anchor.x();
            long dy = p.y() - anchor.y();
            long dz = p.z() - anchor.z();
            return dimension.equals(dim) && dx * dx + dy * dy + dz * dz <= (long) radius * radius;
        }
    }

    /** Null when {@code target} may be walked to, else {@code out_of_region}. */
    public static ActionError check(Region region, String dimension, AreaSpec.Pos target) {
        if (region == null || region.contains(dimension, target)) {
            return null;
        }
        return new ActionError(FailureCode.OUT_OF_REGION, "that is outside this job's area, more than "
                + region.radius() + " blocks from where it started",
                Map.of("target", List.of(target.x(), target.y(), target.z()),
                        "anchor", List.of(region.anchor().x(), region.anchor().y(), region.anchor().z()),
                        "radius", region.radius()));
    }
}
