package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ExcavatePrimitive;
import com.player2.playerengine.seam.GotoPrimitive;
import com.player2.playerengine.seam.Primitive;
import java.util.List;

/** Every primitive the seam dispatches, one instance each; its signature says it is bound. */
public final class Primitives {
    private Primitives() {
    }

    public static List<Primitive> all() {
        return List.of(
                new GotoPrimitive(),
                new ExcavatePrimitive());
    }
}
