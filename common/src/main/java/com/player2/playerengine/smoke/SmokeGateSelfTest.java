package com.player2.playerengine.smoke;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/** The smoke command is absent unless the JVM property enables it. */
public final class SmokeGateSelfTest {
    private SmokeGateSelfTest() {
    }

    public static void main(String[] args) {
        String before = System.getProperty(SmokeGate.PROPERTY);
        try {
            System.clearProperty(SmokeGate.PROPERTY);
            check(!SmokeGate.enabled(), "enabled without the property");
            check(!hasSmoke(SmokeGate.enabled()), "smoke registered without the property");

            System.setProperty(SmokeGate.PROPERTY, "false");
            check(!hasSmoke(SmokeGate.enabled()), "smoke registered with the property false");

            System.setProperty(SmokeGate.PROPERTY, "true");
            check(hasSmoke(SmokeGate.enabled()), "smoke missing with the property true");
        } finally {
            if (before == null) {
                System.clearProperty(SmokeGate.PROPERTY);
            } else {
                System.setProperty(SmokeGate.PROPERTY, before);
            }
        }
        System.out.println("smoke gate self-test: 4 checks passed");
    }

    private static boolean hasSmoke(boolean enabled) {
        boolean[] built = {false};
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(SmokeGate.attach(LiteralArgumentBuilder.literal("playerengine"), enabled, () -> {
            built[0] = true;
            return LiteralArgumentBuilder.literal("smoke");
        }));
        boolean present = dispatcher.getRoot().getChild("playerengine").getChild("smoke") != null;
        check(present == built[0], "harness supplier called without registering, or the reverse");
        return present;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("smoke gate: " + what);
        }
    }
}
