package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

/**
 * TSI Nexus: Card-level narrative insight (Conversational Intelligence v0.2)
 *
 * POST /api/commentary { "card_type": "context|action|capture|disambiguation|analytics|audit",
 *                         "payload": {...} }
 *
 * Thin wrapper around Intelligence.generateCardNarrative - the client already has whatever
 * data a rendered card needs (state/live/links/stream, analytics rows, audit rows, etc.); this
 * endpoint does not fetch anything new, it only asks the LLM to explain what's already there.
 * Called asynchronously, after the primary card has already rendered, so a slow or
 * unavailable Intelligence Module never blocks the actual answer from appearing.
 */
public class Commentary implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String cardType = input.get("card_type") != null ? String.valueOf(input.get("card_type")) : "context";
            JSONObject payload = input.get("payload") instanceof JSONObject ? (JSONObject) input.get("payload") : new JSONObject();

            String narrative = Intelligence.generateCardNarrative(cardType, payload);

            JSONObject result = new JSONObject();
            if (narrative == null || narrative.isBlank()) {
                result.put("success", false);
                result.put("error", "Commentary unavailable right now.");
                OutputProcessor.send(res, 200, result);
                return;
            }

            result.put("success", true);
            result.put("narrative", narrative);
            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            JSONObject result = new JSONObject();
            result.put("success", false);
            result.put("error", "Commentary unavailable right now.");
            OutputProcessor.send(res, 200, result);
        }
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String m, HttpServletRequest req, HttpServletResponse res) { return true; }
}
