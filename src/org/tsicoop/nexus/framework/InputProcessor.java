package org.tsicoop.nexus.framework;

import com.networknt.schema.ValidationMessage;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

public class InputProcessor {
    public final static String REQUEST_DATA = "input_json";
    public final static String AUTH_TOKEN = "auth_token";

    public static void processInput(HttpServletRequest request, HttpServletResponse response){
        String contentType = request.getContentType();
        StringBuilder buffer = new StringBuilder();
        try {
            BufferedReader reader = request.getReader();
            String line = null;
            while ((line = reader.readLine()) != null) {
                //System.out.println("line"+line);
                buffer.append(line);
                buffer.append(System.lineSeparator());
            }
            String data = buffer.toString();
            //System.out.println(data);
            request.setAttribute(REQUEST_DATA, data);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public static boolean processAdminHeader(HttpServletRequest request, HttpServletResponse response) {
        boolean validheader = false;
        JSONObject authToken = null;
        try {
            authToken = getAdminAuthToken(request, response);
            if(authToken != null) {
                request.setAttribute(AUTH_TOKEN, authToken);
                validheader = true;
            }
        }catch (Exception e){}
        return validheader;
    }

    public static boolean processClientHeader(HttpServletRequest req, HttpServletResponse res, String requiredScope) {
        String apiKey    = req.getHeader("X-API-Key");
        String apiSecret = req.getHeader("X-API-Secret");
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) return false;

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        PoolDB pool = null;

        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            ps = conn.prepareStatement(
                "SELECT app_id::text, api_secret_hash, authorized_scopes " +
                "FROM app_access_registry WHERE api_key = ? AND status = 'Active'");
            ps.setString(1, apiKey.trim());
            rs = ps.executeQuery();
            if (!rs.next()) return false;

            if (!SecurityUtil.sha256(apiSecret.trim()).equals(rs.getString("api_secret_hash"))) return false;

            Array scopeArr = rs.getArray("authorized_scopes");
            if (scopeArr == null) return false;
            List<String> scopes = Arrays.asList((String[]) scopeArr.getArray());
            if (!scopes.contains(requiredScope)) return false;

            JSONObject identity = new JSONObject();
            identity.put("app_id",  rs.getString("app_id"));
            identity.put("api_key", apiKey.trim());
            req.setAttribute(AUTH_TOKEN, identity);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (pool != null) pool.cleanup(rs, ps, conn);
        }
    }

    public static String getEmail(HttpServletRequest req){
        JSONObject authToken = null;
        String email = null;
        try {
            authToken = (JSONObject) req.getAttribute(InputProcessor.AUTH_TOKEN);
            email = (String) authToken.get("email");
        }catch(Exception e){
            e.printStackTrace();
        }
        return email;
    }

    public static String getName(HttpServletRequest req){
        JSONObject authToken = null;
        String name = null;
        try {
            authToken = (JSONObject) req.getAttribute(InputProcessor.AUTH_TOKEN);
            name = (String) authToken.get("name");
        }catch(Exception e){
            e.printStackTrace();
        }
        return name;
    }

    public static String getRole(HttpServletRequest req){
        JSONObject authToken = null;
        String role = null;
        try {
            authToken = (JSONObject) req.getAttribute(InputProcessor.AUTH_TOKEN);
            role = (String) authToken.get("role");
        }catch(Exception e){
            e.printStackTrace();
        }
        return role;
    }

    public static String getAccountType(HttpServletRequest req){
        JSONObject authToken = null;
        String type = null;
        try {
            authToken = (JSONObject) req.getAttribute(InputProcessor.AUTH_TOKEN);
            type = (String) authToken.get("type");
        }catch(Exception e){
            e.printStackTrace();
        }
        return type;
    }

    public static String getState(HttpServletRequest req){
        JSONObject authToken = null;
        String state = null;
        try {
            authToken = (JSONObject) req.getAttribute(InputProcessor.AUTH_TOKEN);
            state = (String) authToken.get("state");
        }catch(Exception e){
            e.printStackTrace();
        }
        return state;
    }

    public static String getCity(HttpServletRequest req){
        JSONObject authToken = null;
        String city = null;
        try {
            authToken = (JSONObject) req.getAttribute(InputProcessor.AUTH_TOKEN);
            city = (String) authToken.get("city");
        }catch(Exception e){
            e.printStackTrace();
        }
        return city;
    }



    public static JSONObject getAdminAuthToken(HttpServletRequest req, HttpServletResponse res) throws Exception{
        JSONObject tokenDetails = null;
        String authorization = null;
        StringTokenizer strTok = null;
        String token = null;

        try {
            authorization = req.getHeader("Authorization");
            strTok = new StringTokenizer(authorization, " ");
            strTok.nextToken();
            token = strTok.nextToken();
            //System.out.println("token:"+token);
            if (JWTUtil.isTokenValid(token)) {
                tokenDetails = new JSONObject();
                tokenDetails.put("email",   JWTUtil.getEmailFromToken(token));
                tokenDetails.put("name",    JWTUtil.getNameFromToken(token));
                tokenDetails.put("role",    JWTUtil.getRoleFromToken(token));
                tokenDetails.put("twin_id", JWTUtil.getTwinIdFromToken(token));
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return tokenDetails;
    }

    public static JSONObject getInput(HttpServletRequest req) throws Exception{
        JSONObject input = null;
        String inputs = null;
        try {
            inputs = (String) req.getAttribute(InputProcessor.REQUEST_DATA);
            if(inputs!=null) inputs = inputs.trim();
            //System.out.println("inputs:"+inputs);
            //inputs = applyRules(inputs);
            input = (JSONObject) new JSONParser().parse(inputs);
        }catch(Exception e){
            e.printStackTrace();
        }
        return input;
    }

    public static boolean validate(HttpServletRequest req, HttpServletResponse res) {

        JSONObject input = null;
        Set<ValidationMessage> errors = null;
        boolean valid = true;
        String func = null;

        try {
            input = InputProcessor.getInput(req);
            func = (String) input.get("_func");

            if(func == null){
                OutputProcessor.sendError(res,HttpServletResponse.SC_BAD_REQUEST,"_func missing");
                valid = false;
            }else{
                errors = JSONSchemaValidator.getHandle().validateSchema(func, input);
            }

            if(errors != null && errors.size()>0) {
                OutputProcessor.sendError(res,HttpServletResponse.SC_BAD_REQUEST, errors.toString());
                valid = false;
            }

        }catch(Exception e){
            e.printStackTrace();
            OutputProcessor.sendError(res,HttpServletResponse.SC_BAD_REQUEST,"Unknown input validation error");
            valid = false;
        }
        return valid;
    }

    public static String applyRules(String value) {
        if (value != null && value.trim().length() > 0) {
            try {
                value = URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                //log.error(e.getMessage());
            }
            //value = StringEscapeUtils.unescapeHtml(value);
        } else {
            value = "";
        }
        return value;
    }
}
