package com.player2.playerengine.seam;

import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The queries of stage 2B (§5.3): what each returns from a set-up world, that {@code containers} and
 * {@code contents} see only what the companion could (§5.6), and that every bound signature has a
 * dispatcher. Runs inside {@code companionSelfTest}, after {@link SeamSelfTest}.
 */
public final class QueriesSelfTest {
    private static int checks;

    private QueriesSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        everyBoundSignatureDispatches();
        selfAndOwner();
        lightAt();
        containersSeeOnlyWhatIsVisible();
        contentsOfKnownContainersOnly();
        return checks;
    }

    /** A world with every chunk loaded and nothing in it; the non-block queries ignore it. */
    private static final WorldReader AIR = new WorldReader() {
        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return chunkX != 7;
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    };

    static Outcome ask(String name, Map<String, Object> raw, Primitive.World world, ReadBudget budget) {
        Signature sig = SignatureTable.get(name);
        Coercion.Result c = Coercion.coerce(sig, raw, Coercion.Ids.REGISTRIES);
        if (!c.ok()) {
            return Outcome.failed(c.error(), c.notes());
        }
        QueryQueue q = new QueryQueue();
        Outcome[] out = new Outcome[1];
        q.submit(Queries.build(sig, c.args(), world), o -> out[0] = o);
        for (long tick = 0; out[0] == null && tick < 10_000; tick++) {
            q.tick(AIR, budget, tick);
        }
        return out[0];
    }

    private static Outcome ask(String name, Map<String, Object> raw, Primitive.World world) {
        return ask(name, raw, world, new ReadBudget(ReadBudget.PER_TICK));
    }

    private static void everyBoundSignatureDispatches() {
        SeamTestWorld w = new SeamTestWorld();
        for (Signature s : SignatureTable.all()) {
            if (!s.bound()) {
                continue;
            }
            if (s.kind() == Signature.Kind.QUERY) {
                boolean built;
                try {
                    Queries.build(s, Map.of(), w);
                    built = true;
                } catch (IllegalArgumentException e) {
                    built = false;
                } catch (RuntimeException e) {
                    // Built lazily over missing args is fine; only "not a bound query" is a gap.
                    built = true;
                }
                require(built, "bound query " + s.name() + " has a builder");
            } else {
                Primitive p = Seam.primitive(s.name());
                require(p != null && p.signature() == s, "bound primitive " + s.name() + " is dispatched");
            }
        }
    }

    private static void selfAndOwner() {
        SeamTestWorld w = new SeamTestWorld();
        w.position = new Vec3(10.7, 64, -3.2);
        w.carry("cobblestone", 40).carry("torch", 5);
        w.lastArea = new AreaSpec.Box(0, 60, 0, 2, 61, 2, AreaSpec.Facing.NORTH);
        Outcome inv = ask("inventory", Map.of(), w);
        require(inv.ok() && inv.value().equals(Map.of("cobblestone", 40, "torch", 5)), "inventory: " + inv);
        Outcome count = ask("count", Map.of("item", "Torches"), w);
        require(count.ok() && Integer.valueOf(5).equals(count.value()),
                "count(torch): " + count);
        Outcome none = ask("count", Map.of("item", "diamond"), w);
        require(none.ok() && Integer.valueOf(0).equals(none.value()), "count of what it lacks is 0: " + none);
        Outcome at = ask("position", Map.of(), w);
        require(at.ok() && at.value().equals(new AreaSpec.Pos(10, 64, -4)), "position floors to the block: " + at);
        Outcome away = ask("owner_pos", Map.of(), w);
        require(!away.ok() && away.error().code() == FailureCode.NOT_FOUND, "an absent owner is not_found: " + away);
        w.owner = new Vec3(1.5, 70, 2.5);
        w.ownerFacing = AreaSpec.Facing.EAST;
        Outcome owner = ask("owner_pos", Map.of(), w);
        require(owner.ok() && owner.value().equals(new AreaSpec.Pos(1, 70, 2)), "owner_pos: " + owner);
        Outcome facing = ask("owner_facing", Map.of(), w);
        require(facing.ok() && facing.value() == AreaSpec.Facing.EAST, "owner_facing: " + facing);
        Outcome area = ask("last_area", Map.of(), w);
        require(area.ok() && area.value().equals(w.lastArea), "last_area: " + area);
    }

    private static void lightAt() {
        SeamTestWorld w = new SeamTestWorld();
        w.lightLevels.put(new AreaSpec.Pos(3, 64, 3), 4);
        Outcome dark = ask("light_at", Map.of("p", "3 64 3"), w);
        require(dark.ok() && Integer.valueOf(4).equals(dark.value()), "light_at: " + dark);
        Outcome far = ask("light_at", Map.of("p", List.of(7 * 16 + 1, 64, 0)), w);
        require(!far.ok() && far.error().code() == FailureCode.NOT_LOADED, "light_at unloaded is not_loaded: " + far);
    }

    private static void containersSeeOnlyWhatIsVisible() {
        SeamTestWorld w = new SeamTestWorld();
        w.position = new Vec3(0.5, 64, 0.5);
        ContainerHandle dbl = w.chest(new AreaSpec.Pos(3, 64, 0), new AreaSpec.Pos(4, 64, 0), 54);
        ContainerHandle near = w.chest(new AreaSpec.Pos(1, 64, 1), null, 27);
        ContainerHandle buried = w.chest(new AreaSpec.Pos(0, 60, 0), null, 27);
        ContainerHandle buriedKnown = w.chest(new AreaSpec.Pos(0, 58, 2), null, 27);
        ContainerHandle behindWall = w.chest(new AreaSpec.Pos(-5, 64, 0), null, 27);
        ContainerHandle far = w.chest(new AreaSpec.Pos(40, 64, 0), null, 27);
        w.hidden.add(buried.pos());
        w.hidden.add(buriedKnown.pos());
        w.known.add(buriedKnown);
        w.inSight.add(dbl);
        w.inSight.add(near);
        w.inSight.add(far);
        Outcome o = ask("containers", Map.of("radius", 16), w);
        require(o.ok() && o.value().equals(List.of(near, dbl, buriedKnown)),
                "containers lists the visible and the known, a double chest once, nearest first: " + o);
        require(!((List<?>) o.value()).contains(buried) && !((List<?>) o.value()).contains(behindWall),
                "an enclosed or out-of-sight container is not listed");

        // The budget: each candidate is charged, and a spent tick parks the scan rather than overrunning.
        ReadBudget tight = new ReadBudget(Queries.Containers.COST);
        Outcome slow = ask("containers", Map.of("radius", 16), w, tight);
        require(slow.equals(o) && tight.peak() <= Queries.Containers.COST, "a tight budget spreads the scan over ticks: "
                + slow);

        // At most 16 raycasts per query, however many containers are exposed.
        SeamTestWorld crowd = new SeamTestWorld();
        crowd.position = new Vec3(0.5, 64, 0.5);
        for (int i = 0; i < 30; i++) {
            ContainerHandle h = crowd.chest(new AreaSpec.Pos(i - 15, 64, 5), null, 27);
            crowd.inSight.add(h);
        }
        Outcome many = ask("containers", Map.of("radius", 16), crowd);
        require(many.ok() && ((List<?>) many.value()).size() == SeamPerception.MAX_RAYCASTS
                        && crowd.raycasts == SeamPerception.MAX_RAYCASTS,
                "a query casts at most " + SeamPerception.MAX_RAYCASTS + " rays: " + crowd.raycasts);
    }

    private static void contentsOfKnownContainersOnly() {
        SeamTestWorld w = new SeamTestWorld();
        ContainerHandle dbl = w.chest(new AreaSpec.Pos(3, 64, 0), new AreaSpec.Pos(4, 64, 0), 50);
        w.containers.get(dbl).put("cobblestone", 64);
        w.containers.get(dbl).put("dirt", 12);
        w.inSight.add(dbl);
        Outcome unknown = ask("contents", Map.of("c", "4 64 0"), w);
        require(!unknown.ok() && unknown.error().code() == FailureCode.DENIED,
                "contents of a container it never opened is denied, even in sight: " + unknown);
        w.known.add(dbl);
        Outcome viaOther = ask("contents", Map.of("c", "4 64 0"), w);
        require(viaOther.ok() && viaOther.value().equals(Map.of("cobblestone", 64, "dirt", 12)),
                "contents by either half is the whole double chest (E8): " + viaOther);
        Outcome nothing = ask("contents", Map.of("c", "9 64 9"), w);
        require(!nothing.ok() && nothing.error().code() == FailureCode.NO_CONTAINER, "no container: " + nothing);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("queries self-test failed: " + message);
        }
    }
}
