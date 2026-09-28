package com.player2.playerengine.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;

/**
 * Companion ticket holds (R9): keyed by dimension, chunk and companion; all 9 released on reset;
 * kept through server stop; a loaded hold claimed by its companion or swept when it is stale. The
 * store here is the ticket store's own state, one owner-tagged ticket per (dimension, companion,
 * chunk), as NeoForge's entity tickets are.
 */
public final class TicketBookSelfTest {
    private static int checks;

    private TicketBookSelfTest() {
    }

    private record Ticket(Object dimension, UUID companion, long chunk) {
    }

    private static final class Tickets implements TicketBook.Store {
        final Set<Ticket> live = new HashSet<>();

        @Override
        public void add(Object dimension, UUID companion, long chunk) {
            live.add(new Ticket(dimension, companion, chunk));
        }

        @Override
        public void remove(Object dimension, UUID companion, long chunk) {
            live.remove(new Ticket(dimension, companion, chunk));
        }

        int count(Object dimension, UUID companion) {
            int n = 0;
            for (Ticket t : live) {
                if (t.dimension().equals(dimension) && t.companion().equals(companion)) {
                    n++;
                }
            }
            return n;
        }
    }

    private static final class OwnerMap implements TicketBook.Owners {
        final Map<UUID, UUID> map = new HashMap<>();

        @Override
        public UUID get(UUID companion) {
            return map.get(companion);
        }

        @Override
        public void put(UUID companion, UUID owner) {
            map.put(companion, owner);
        }

        @Override
        public void remove(UUID companion) {
            map.remove(companion);
        }
    }

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final UUID ADA = uuid("ada");
    private static final UUID BEA = uuid("bea");
    private static final UUID OWNER = uuid("owner");

    public static int runAll() {
        checks = 0;
        resetReleasesAllNine();
        dimensionIsPartOfTheKey();
        movingReleasesOnlyWhatIsLeft();
        stopKeepsTheHold();
        loadedHoldsAreClaimedOrSwept();
        return checks;
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Long> threeByThree(int cx, int cz) {
        List<Long> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                out.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }
        return out;
    }

    private static void resetReleasesAllNine() {
        Tickets t = new Tickets();
        OwnerMap owners = new OwnerMap();
        TicketBook book = new TicketBook(t, owners);
        book.holdExactly(OVERWORLD, ADA, OWNER, threeByThree(4, 4));
        require(t.count(OVERWORLD, ADA) == 9, "a hold is 9 tickets");
        require(OWNER.equals(owners.get(ADA)), "the owner is recorded");
        int released = book.releaseAll(ADA);
        require(released == 9 && t.live.isEmpty(),
                "reset releases all 9 (released " + released + ", left " + t.live.size() + ")");
        require(owners.get(ADA) == null, "and forgets the owner");
    }

    private static void dimensionIsPartOfTheKey() {
        Tickets t = new Tickets();
        TicketBook book = new TicketBook(t, new OwnerMap());
        book.holdExactly(OVERWORLD, ADA, OWNER, threeByThree(0, 0));
        book.holdExactly(NETHER, BEA, OWNER, threeByThree(0, 0));
        require(t.count(OVERWORLD, ADA) == 9 && t.count(NETHER, BEA) == 9,
                "two companions at the same x,z in two dimensions each hold 9");
        book.releaseAll(BEA);
        require(t.count(OVERWORLD, ADA) == 9 && t.count(NETHER, BEA) == 0,
                "releasing the nether one leaves the overworld one");
        // The same companion through a portal to the same coordinates: its old hold is released.
        book.holdExactly(NETHER, ADA, OWNER, threeByThree(0, 0));
        require(t.count(NETHER, ADA) == 9, "the hold follows it into the nether (" + t.count(NETHER, ADA) + ")");
        require(t.count(OVERWORLD, ADA) == 0,
                "and leaves nothing in the overworld (" + t.count(OVERWORLD, ADA) + ")");
    }

    private static void movingReleasesOnlyWhatIsLeft() {
        Tickets t = new Tickets();
        TicketBook book = new TicketBook(t, new OwnerMap());
        book.holdExactly(OVERWORLD, ADA, OWNER, threeByThree(0, 0));
        book.holdExactly(OVERWORLD, BEA, OWNER, threeByThree(1, 0));
        book.holdExactly(OVERWORLD, ADA, OWNER, threeByThree(5, 0));
        require(t.count(OVERWORLD, ADA) == 9, "the moved hold is 9 tickets");
        require(!t.live.contains(new Ticket(OVERWORLD, ADA, ChunkPos.asLong(0, 0))), "the chunk it left is released");
        require(t.count(OVERWORLD, BEA) == 9 && t.live.contains(new Ticket(OVERWORLD, BEA, ChunkPos.asLong(0, 0))),
                "another companion's ticket on a chunk it left is kept");
    }

    private static void stopKeepsTheHold() {
        Tickets t = new Tickets();
        TicketBook book = new TicketBook(t, new OwnerMap());
        book.holdExactly(OVERWORLD, ADA, OWNER, threeByThree(2, 2));
        book.stopping();
        // Player2NPC dismisses every companion as its owner is disconnected at stop.
        book.releaseAll(ADA);
        book.holdExactly(OVERWORLD, ADA, OWNER, threeByThree(9, 9));
        require(t.count(OVERWORLD, ADA) == 9 && t.live.contains(new Ticket(OVERWORLD, ADA, ChunkPos.asLong(2, 2))),
                "the hold is saved as it stood when the server began to stop");
    }

    private static void loadedHoldsAreClaimedOrSwept() {
        Tickets t = new Tickets();
        OwnerMap owners = new OwnerMap();
        UUID ghost = uuid("deleted");
        UUID away = uuid("owner-offline");
        UUID awayOwner = uuid("offline");
        owners.put(ADA, OWNER);
        owners.put(BEA, OWNER);
        owners.put(away, awayOwner);
        List<UUID> saved = List.of(ADA, BEA, ghost, away);
        for (int i = 0; i < saved.size(); i++) {
            for (long chunk : threeByThree(10 * i, 7)) {
                t.add(OVERWORLD, saved.get(i), chunk);
            }
        }
        TicketBook book = new TicketBook(t, owners);
        for (int i = 0; i < saved.size(); i++) {
            book.loaded(OVERWORLD, saved.get(i), threeByThree(10 * i, 7));
        }
        Set<UUID> online = Set.of(OWNER);
        List<UUID> first = book.sweep(online::contains);
        require(first.equals(List.of(ghost)) && t.count(OVERWORLD, ghost) == 0,
                "a loaded hold with no recorded owner is released at the first sweep: " + first);
        // Ada comes back somewhere else; Bea never does while her owner is online.
        book.holdExactly(OVERWORLD, ADA, OWNER, threeByThree(40, 40));
        require(t.count(OVERWORLD, ADA) == 9 && t.live.contains(new Ticket(OVERWORLD, ADA, ChunkPos.asLong(40, 40))),
                "a returning companion claims its hold and moves it");
        List<UUID> swept = new ArrayList<>();
        for (int i = 0; i < TicketBook.STALE_SWEEPS + 2; i++) {
            swept.addAll(book.sweep(online::contains));
        }
        require(swept.equals(List.of(BEA)) && t.count(OVERWORLD, BEA) == 0,
                "a companion that never returns while its owner is online is released: " + swept);
        require(t.count(OVERWORLD, ADA) == 9, "a claimed hold is never swept");
        require(t.count(OVERWORLD, away) == 9, "a hold whose owner stays offline is kept (R9)");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("ticket book self-test failed: " + message);
        }
    }
}
