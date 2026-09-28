package com.player2.playerengine.player2api.utils;

import com.google.gson.JsonObject;

/**
 * Lightweight harness for LLM JSON cleanup cases.
 *
 * <p>Not invoked automatically; useful for quick manual verification.
 */
public final class UtilsJsonSelfTest {
    private UtilsJsonSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        JsonObject direct = Utils.parseCleanedJson("{\"say\":\"hi\",\"program\":\"api.wait(20);\"}");
        assert "api.wait(20);".equals(direct.get("program").getAsString());

        JsonObject doubleBraced = Utils.parseCleanedJson(
                "{{\"say\":\"j'arrive\",\"program\":\"api.follow_owner(4, 60);\"}}");
        assert "api.follow_owner(4, 60);".equals(doubleBraced.get("program").getAsString());

        JsonObject fencedDoubleBraced = Utils.parseCleanedJson(
                "```json\n{{\"say\":\"salut\"}}\n```");
        assert "salut".equals(fencedDoubleBraced.get("say").getAsString());
    }
}
