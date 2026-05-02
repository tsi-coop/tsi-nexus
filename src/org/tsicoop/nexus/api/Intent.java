package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TSI Nexus: Universal Intent Resolver
 * The bridge between Natural Language/Commands and the Liquid Interface.
 * Designed to be vertical-agnostic: handles any entity type or action via metadata.
 */
public class Intent implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = req.getHeader("X-DX-FUNCTION");

            if ("resolve_intent".equalsIgnoreCase(func)) {
                String rawIntent = (String) input.get("intent");
                OutputProcessor.send(res, 200, resolveToAdaptiveUI(rawIntent));
            } else {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Invalid function header.", req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Intent Resolution Failed", e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * Maps intent to UI components.
     * Uses a polymorphic approach: The UI behaves differently based on the 'type' of the twin.
     */
    private JSONObject resolveToAdaptiveUI(String intent) {
        JSONObject response = new JSONObject();
        JSONArray components = new JSONArray();
        String cleanIntent = intent.trim();

        // 1. DISAMBIGUATION / CONTEXT LOOKUP (@target)
        // If the user mentions an entity, always trigger a Context Card.
        // The Nexus 'Context' API will determine if it's a Customer, Machine, or Doctor.
        if (cleanIntent.contains("@")) {
            String target = extractTarget(cleanIntent);
            // Polymorphic Card: Renders differently based on Twin Type (FSM State)
            components.add(createComponent("universal_context_card", "{\"target\":\"" + target + "\"}"));
        }

        // 2. UNIVERSAL COMMAND PATTERN (/command @target value)
        // This collapses all verbs: /disburse, /repair, /admit, /set_status
        if (cleanIntent.startsWith("/")) {
            String[] parts = cleanIntent.split("\\s+", 3);
            if (parts.length >= 2) {
                String actionVerb = parts[0].substring(1).toUpperCase(); // e.g., DISBURSE
                String target = parts[1];
                String value = (parts.length > 2) ? parts[2] : "";

                JSONObject props = new JSONObject();
                props.put("action_type", actionVerb);
                props.put("target", target);
                props.put("value", value);
                props.put("intent_raw", cleanIntent);
                
                // Returns a 'Handshake' component that binds to the Governance Pillar
                components.add(createComponent("universal_action_confirm", props.toJSONString()));
            }
        } 
        
        // 3. SEMANTIC SEARCH / KNOWLEDGE RETRIEVAL
        // If it's not a command or specific target, treat it as a natural language query.
        else if (!cleanIntent.contains("@") && !cleanIntent.startsWith("/")) {
            JSONObject props = new JSONObject();
            props.put("query", cleanIntent);
            components.add(createComponent("nexus_semantic_results", props.toJSONString()));
        }

        response.put("success", true);
        response.put("intent_captured", cleanIntent);
        response.put("components", components); 
        return response;
    }

    /**
     * Generates the UI Schema for the Liquid Interface.
     */
    private JSONObject createComponent(String type, String propsJson) {
        JSONObject comp = new JSONObject();
        comp.put("component_type", type);
        comp.put("props", propsJson); 
        return comp;
    }

    /**
     * Uses Regex to extract handles (e.g., @satish or @machine_01).
     */
    private String extractTarget(String intent) {
        Pattern pattern = Pattern.compile("@([\\w\\.]+)");
        Matcher matcher = pattern.matcher(intent);
        if (matcher.find()) {
            return "@" + matcher.group(1);
        }
        return "unknown";
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String m, HttpServletRequest req, HttpServletResponse res) { return true; }
}