package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Primitive;
import com.player2.playerengine.seam.Signature;
import com.player2.playerengine.seam.SignatureTable;
import java.util.Map;

/**
 * A primitive's defaults: its signature by name, no command-line form, and nothing to refuse before
 * it starts. A primitive overrides what it has.
 */
abstract class Base implements Primitive {
    private final String name;

    Base(String name) {
        this.name = name;
    }

    @Override
    public Signature signature() {
        return SignatureTable.get(name);
    }

    @Override
    public LineArgs fromLine(String argsText, Context ctx) {
        return null;
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        return null;
    }
}
