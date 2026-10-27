package dev.horizon.mobile;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Ely.by account (a Yggdrasil-style auth server). Works on servers that use Ely.by, and offline. */
final class Ely {
    static final String BASE = "https://authserver.ely.by";
    static final String JOIN_URL = BASE + "/session/join";

    static boolean signedIn(SharedPreferences p) { return p.getString("ely_token", null) != null; }
    static String name(SharedPreferences p) { return signedIn(p) ? p.getString("ely_name", null) : null; }

    /** code: the two-factor code, or empty. It goes after the password, "password:code". */
    static void signIn(SharedPreferences p, String user, String password, String code) throws Exception {
        String client = p.getString("ely_client", UUID.randomUUID().toString().replace("-", ""));
        JSONObject body = new JSONObject().put("username", user)
                .put("password", code == null || code.isEmpty() ? password : password + ":" + code)
                .put("clientToken", client).put("requestUser", true);
        JSONObject r = post("/auth/authenticate", body);
        JSONObject profile = r.getJSONObject("selectedProfile");
        p.edit().putString("ely_token", r.getString("accessToken"))
                .putString("ely_client", r.optString("clientToken", client))
                .putString("ely_uuid", profile.getString("id"))
                .putString("ely_name", profile.getString("name")).apply();
    }

    /** A fresh access token without the password. Throws when the account must sign in again. */
    static void refresh(SharedPreferences p) throws Exception {
        JSONObject body = new JSONObject().put("accessToken", p.getString("ely_token", ""))
                .put("clientToken", p.getString("ely_client", ""));
        JSONObject r = post("/auth/refresh", body);
        p.edit().putString("ely_token", r.getString("accessToken")).apply();
    }

    static void signOut(SharedPreferences p) {
        p.edit().remove("ely_token").remove("ely_uuid").remove("ely_name").apply();
    }

    /** The file the game reads once at start (name, uuid, token); owner-only, as the client insists. */
    static void writeSession(SharedPreferences p, File f) throws IOException {
        try {
            JSONObject o = new JSONObject().put("name", p.getString("ely_name", ""))
                    .put("uuid", p.getString("ely_uuid", ""))
                    .put("access_token", p.getString("ely_token", "")).put("user_type", "ely");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(o.toString()); }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        f.setReadable(false, false); f.setReadable(true, true);
        f.setWritable(false, false); f.setWritable(true, true);
        f.setExecutable(false, false);
    }

    private static JSONObject post(String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (in != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) text.append(line);
            }
        }
        if (code >= 400) {
            String msg = text.toString();
            try { msg = new JSONObject(msg).optString("errorMessage", msg); } catch (Exception ignored) {}
            throw new IOException(msg.isEmpty() ? "HTTP " + code : msg);
        }
        return new JSONObject(text.toString());
    }
}
