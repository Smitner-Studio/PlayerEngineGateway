package com.player2.playerengine.tasks.construction.area;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The box grammar shared by the area commands, and the size and distance bounds every box must meet.
 * Pure: the command supplies the anchor positions and world limits.
 *
 * <pre>
 * &lt;dx&gt; &lt;dy&gt; &lt;dz&gt; [anchor=here|owner|last|x,y,z] [facing=north|south|east|west] [confirm=yes]
 * &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; [confirm=yes]
 * </pre>
 *
 * A relative box has its floor at the anchor's feet and rises {@code dy}. From an entity anchor
 * ({@code here}, {@code owner}) it starts one block ahead in {@code facing}, runs
 * {@code dz} deep along it and {@code dx} wide across it, centred (an even width puts the extra
 * column on the right). {@code anchor=last} does the same from the last area's edge in
 * {@code facing}, so the new box adjoins it ("make the room 5 longer to the north"). From a coordinate
 * anchor it is centred on that block and {@code facing} is ignored.
 */
public final class AreaSpec {
    public static final int MAX_AXIS = 32;
    public static final int MAX_HEIGHT = 8;
    public static final int MAX_EXCAVATE_CELLS = 2048;
    public static final int MAX_FILL_CELLS = 512;
    public static final int MAX_DISTANCE = 48;
    /** Blocks kept between the box floor and the world's bottom (bedrock, void). */
    public static final int FLOOR_MARGIN = 6;

    public enum Facing {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

        final int fx;
        final int fz;

        Facing(int fx, int fz) {
            this.fx = fx;
            this.fz = fz;
        }

        /** The cardinal facing nearest a Minecraft yaw (0 = south, 90 = west). */
        public static Facing fromYaw(float yaw) {
            int q = Math.floorMod(Math.round(yaw / 90.0F), 4);
            return switch (q) {
                case 0 -> SOUTH;
                case 1 -> WEST;
                case 2 -> NORTH;
                default -> EAST;
            };
        }

        static Facing parse(String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public record Pos(int x, int y, int z) {
    }

    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Facing facing) {
        public int sizeX() {
            return maxX - minX + 1;
        }

        public int sizeY() {
            return maxY - minY + 1;
        }

        public int sizeZ() {
            return maxZ - minZ + 1;
        }

        public long cells() {
            return (long) sizeX() * sizeY() * sizeZ();
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        public String corners() {
            return minX + " " + minY + " " + minZ + " " + maxX + " " + maxY + " " + maxZ;
        }
    }

    /** Where the entity anchors are this tick. {@code owner} and {@code last} may be null. */
    public record Anchors(Pos here, Facing hereFacing, Pos owner, Facing ownerFacing, Box last) {
    }

    /** What the arguments say, before anchors are known. */
    public record Request(String block, int[] numbers, String anchor, Facing facing, boolean confirm) {
    }

    /** A box, or the reason there is none (phrased for the model to act on). */
    public record Resolved(Box box, String error) {
        static Resolved ok(Box b) {
            return new Resolved(b, null);
        }

        static Resolved error(String e) {
            return new Resolved(null, e);
        }
    }

    private AreaSpec() {
    }

    /**
     * @param withBlock true for commands whose first argument is a block id ({@code fill})
     * @return the request, or null with {@code errors[0]} set
     */
    public static Request parse(String args, boolean withBlock, String[] errors) {
        String[] tokens = args == null ? new String[0] : args.trim().split("\\s+");
        List<Integer> numbers = new ArrayList<>();
        String anchor = null;
        Facing facing = null;
        boolean confirm = false;
        String block = null;
        int i = 0;
        if (withBlock) {
            if (tokens.length == 0 || tokens[0].isBlank() || tokens[0].contains("=") || isInt(tokens[0])) {
                errors[0] = "name the block to place first, e.g. fill cobblestone 9 1 9";
                return null;
            }
            block = tokens[0].toLowerCase(Locale.ROOT);
            i = 1;
        }
        for (; i < tokens.length; i++) {
            String t = tokens[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq > 0) {
                String key = t.substring(0, eq).toLowerCase(Locale.ROOT);
                String value = t.substring(eq + 1).toLowerCase(Locale.ROOT);
                switch (key) {
                    case "anchor" -> anchor = value;
                    case "facing" -> {
                        facing = Facing.parse(value);
                        if (facing == null) {
                            errors[0] = "facing must be north, south, east or west";
                            return null;
                        }
                    }
                    case "confirm" -> confirm = value.equals("yes") || value.equals("true");
                    default -> {
                        errors[0] = "unknown option '" + key + "'";
                        return null;
                    }
                }
            } else if (isInt(t)) {
                numbers.add(Integer.parseInt(t));
            } else {
                errors[0] = "'" + t + "' is not a number or option";
                return null;
            }
        }
        if (numbers.size() != 3 && numbers.size() != 6) {
            errors[0] = "give a size (dx dy dz) or two corners (x1 y1 z1 x2 y2 z2)";
            return null;
        }
        if (numbers.size() == 6 && (anchor != null || facing != null)) {
            errors[0] = "corners take no anchor or facing";
            return null;
        }
        int[] n = numbers.stream().mapToInt(Integer::intValue).toArray();
        return new Request(block, n, anchor, facing, confirm);
    }

    public static Resolved resolve(Request r, Anchors a) {
        int[] n = r.numbers();
        if (n.length == 6) {
            return Resolved.ok(new Box(Math.min(n[0], n[3]), Math.min(n[1], n[4]), Math.min(n[2], n[5]),
                    Math.max(n[0], n[3]), Math.max(n[1], n[4]), Math.max(n[2], n[5]),
                    a.hereFacing()));
        }
        int dx = n[0];
        int dy = n[1];
        int dz = n[2];
        if (dx < 1 || dy < 1 || dz < 1) {
            return Resolved.error("each size must be at least 1");
        }
        String anchor = r.anchor() == null ? "here" : r.anchor();
        Pos base;
        Facing facing;
        switch (anchor) {
            case "here" -> {
                base = a.here();
                facing = r.facing() != null ? r.facing() : a.hereFacing();
            }
            case "owner" -> {
                if (a.owner() == null) {
                    return Resolved.error("the owner is not in this world right now");
                }
                base = a.owner();
                facing = r.facing() != null ? r.facing() : a.ownerFacing();
            }
            case "last" -> {
                if (a.last() == null) {
                    return Resolved.error("there is no last area yet");
                }
                Box l = a.last();
                facing = r.facing() != null ? r.facing() : l.facing();
                // Stand on the last area's far edge in the facing, so the new box adjoins it.
                int cx = (l.minX() + l.maxX()) / 2;
                int cz = (l.minZ() + l.maxZ()) / 2;
                base = switch (facing) {
                    case NORTH -> new Pos(cx, l.minY(), l.minZ());
                    case SOUTH -> new Pos(cx, l.minY(), l.maxZ());
                    case EAST -> new Pos(l.maxX(), l.minY(), cz);
                    case WEST -> new Pos(l.minX(), l.minY(), cz);
                };
            }
            default -> {
                String[] xyz = anchor.split(",");
                if (xyz.length != 3 || !isInt(xyz[0]) || !isInt(xyz[1]) || !isInt(xyz[2])) {
                    return Resolved.error("anchor must be here, owner, last or x,y,z");
                }
                int cx = Integer.parseInt(xyz[0]);
                int cy = Integer.parseInt(xyz[1]);
                int cz = Integer.parseInt(xyz[2]);
                int x0 = cx - (dx - 1) / 2;
                int z0 = cz - (dz - 1) / 2;
                return Resolved.ok(new Box(x0, cy, z0, x0 + dx - 1, cy + dy - 1, z0 + dz - 1,
                        r.facing() != null ? r.facing() : a.hereFacing()));
            }
        }
        // Along the facing: 1..dz ahead. Across it: centred, extra column to the right.
        int rightX = -facing.fz;
        int rightZ = facing.fx;
        int leftSpan = (dx - 1) / 2;
        int x1 = base.x() + facing.fx - rightX * leftSpan;
        int z1 = base.z() + facing.fz - rightZ * leftSpan;
        int x2 = base.x() + facing.fx * dz + rightX * (dx - 1 - leftSpan);
        int z2 = base.z() + facing.fz * dz + rightZ * (dx - 1 - leftSpan);
        return Resolved.ok(new Box(Math.min(x1, x2), base.y(), Math.min(z1, z2),
                Math.max(x1, x2), base.y() + dy - 1, Math.max(z1, z2), facing));
    }

    /**
     * Size, height and distance bounds.
     *
     * @return null when the box is allowed, else the reason
     */
    public static String checkBounds(Box b, int maxCells, Pos companion, int minBuildY, int maxBuildY) {
        if (b.sizeX() > MAX_AXIS || b.sizeZ() > MAX_AXIS) {
            return "that is wider than " + MAX_AXIS + " blocks; split it into parts";
        }
        if (b.sizeY() > MAX_HEIGHT) {
            return "that is taller than " + MAX_HEIGHT + " blocks; split it into layers";
        }
        if (b.cells() > maxCells) {
            return "that is " + b.cells() + " blocks, more than " + maxCells + " in one go; split it into parts";
        }
        double cx = (b.minX() + b.maxX()) / 2.0 - companion.x();
        double cy = (b.minY() + b.maxY()) / 2.0 - companion.y();
        double cz = (b.minZ() + b.maxZ()) / 2.0 - companion.z();
        if (Math.sqrt(cx * cx + cy * cy + cz * cz) > MAX_DISTANCE) {
            return "that is more than " + MAX_DISTANCE + " blocks away; go there first";
        }
        if (b.minY() < minBuildY + FLOOR_MARGIN) {
            return "that is too close to the bottom of the world";
        }
        if (b.maxY() + 3 > maxBuildY) {
            return "that is too close to the top of the world";
        }
        return null;
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty() || s.length() > 9) {
            return false;
        }
        int start = s.charAt(0) == '-' ? 1 : 0;
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
