package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * TSI Nexus: Intent Resolver (The A2UI Bridge)
 * Translates natural language intent into adaptive UI components and actions.
 */
public class Intent implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = req.getHeader("X-DX-FUNCTION");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing function header.", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "resolve_intent":
                    // The primary entry point for the Command-K bar
                    String rawIntent = (String) input.get("intent");
                    OutputProcessor.send(res, 200, resolveToAdaptiveUI(rawIntent));
                    break;

                default:
                    OutputProcessor.errorResponse(res, 400, "Unknown Function", func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Intent Error", e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * Maps natural language strings to UI Components.
     * Collapses Intent into Adaptive UI schema.
     */
    private JSONObject resolveToAdaptiveUI(String intent) {
        JSONObject response = new JSONObject();
        JSONArray components = new JSONArray();

        String cleanIntent = intent.toLowerCase().trim();

        // 1. Action Pattern: State Mutation (/set_status @target value)
        if (cleanIntent.startsWith("/set_status")) {
            String[] parts = cleanIntent.split(" ");
            if (parts.length >= 3) {
                JSONObject props = new JSONObject();
                props.put("target", parts[1]);      // e.g., @satish
                props.put("new_status", parts[2]);  // e.g., busy
                props.put("intent_raw", cleanIntent);
                
                // Return the confirmation card to bridge to Governance
                components.add(createComponent("action_confirm_card", props.toJSONString()));
            }
        } 
        
        // 2. Query Pattern: Health/Context Check (@target or /check)
        else if (cleanIntent.startsWith("/check") || cleanIntent.contains("@")) {
            String targetId = extractTarget(cleanIntent);
            components.add(createComponent("twin_context_card", "{\"target\":\"" + targetId + "\"}"));
        } 
        
        // 3. Governance Pattern: Financial/Policy Action
        else if (cleanIntent.contains("limit") || cleanIntent.contains("disburse")) {
            components.add(createComponent("policy_validation_form", "{\"intent\":\"" + cleanIntent + "\"}"));
        } 
        
        // 4. Default: Search / Semantic Interaction
        else {
            components.add(createComponent("nexus_search_results", "{\"query\":\"" + cleanIntent + "\"}"));
        }

        response.put("success", true);
        response.put("intent_captured", cleanIntent);
        response.put("components", components); 
        return response;
    }

    /**
     * Helper to wrap UI schema for the Liquid frontend.
     */
    private JSONObject createComponent(String type, String propsJson) {
        JSONObject comp = new JSONObject();
        comp.put("component_type", type);
        comp.put("props", propsJson); 
        return comp;
    }

    /**
     * Crude extraction of @mention for the prototype.
     */
    private String extractTarget(String intent) {
        if (intent.contains("@")) {
            int start = intent.indexOf("@");
            int spaceEnd = intent.indexOf(" ", start);
            return (spaceEnd == -1) ? intent.substring(start) : intent.substring(start, spaceEnd);
        }
        return "unknown";
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    
    @Override 
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        // Prototype bypass - in production, validate JWT or Handshake Token
        return true;
    }
}