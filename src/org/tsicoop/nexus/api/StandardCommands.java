package org.tsicoop.nexus.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;

final class StandardCommands {
    static final Set<String> MANDATORY = Set.of(
        "analyze", "compare", "approve", "reject", "escalate", "hold", "record"
    );

    private static final String[][] COMMANDS = {
        {"analyze",  "Analyze",  "@entity",         "Pull up profile, context, and performance details for an entity.",        "ANALYZE",  "universal_action_confirm", "false", "false"},
        {"compare",  "Compare",  "@entity @entity", "Compare two entities side by side using available institutional data.",   "COMPARE",  "universal_action_confirm", "true",  "false"},
        {"approve",  "Approve",  "@entity",         "Approve a pending request or workflow for the selected entity.",          "APPROVE",  "universal_action_confirm", "false", "false"},
        {"reject",   "Reject",   "@entity",         "Reject a pending request or workflow for the selected entity.",           "REJECT",   "universal_action_confirm", "false", "false"},
        {"escalate", "Escalate", "@entity",         "Escalate an entity or case for supervisory review.",                      "ESCALATE", "universal_action_confirm", "false", "false"},
        {"hold",     "Hold",     "@entity",         "Place an entity or case on operational hold pending review.",             "HOLD",     "universal_action_confirm", "false", "false"},
        {"record",   "Record",   "@entity",         "Record a structured interaction or field update for the selected entity.", "RECORD",   "interaction_capture_form", "false", "false"}
    };

    private StandardCommands() {}

    static void ensure(Connection conn) throws SQLException {
        String sql =
            "INSERT INTO command_manifest " +
            "(command_verb, label, args_hint, hint, action_type, component_type, multi_target, has_value, is_active) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::boolean, ?::boolean, TRUE) " +
            "ON CONFLICT (command_verb) DO UPDATE SET " +
            "label=EXCLUDED.label, args_hint=EXCLUDED.args_hint, hint=EXCLUDED.hint, " +
            "action_type=EXCLUDED.action_type, component_type=EXCLUDED.component_type, " +
            "multi_target=EXCLUDED.multi_target, has_value=EXCLUDED.has_value, is_active=TRUE";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] c : COMMANDS) {
                ps.setString(1, c[0]);
                ps.setString(2, c[1]);
                ps.setString(3, c[2]);
                ps.setString(4, c[3]);
                ps.setString(5, c[4]);
                ps.setString(6, c[5]);
                ps.setString(7, c[6]);
                ps.setString(8, c[7]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static boolean isMandatory(String verb) {
        return verb != null && MANDATORY.contains(verb.trim().toLowerCase());
    }
}
