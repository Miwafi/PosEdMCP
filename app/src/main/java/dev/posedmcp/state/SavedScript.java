package dev.posedmcp.state;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A script the user chose to keep.
 *
 * <p>Saved scripts are a small library rather than a log: the agent files one
 * when the user asks for something to be repeatable, and the automation page is
 * where the user runs or removes it. Everything the page shows about a script -
 * what it is for, and what happened the last time it ran - lives here, so the
 * page never has to guess or re-run something to describe it.
 */
public final class SavedScript {

    public String id = "";
    public String name = "";
    /** The application the script runs inside. */
    public String packageName = "";
    public String source = "";
    /** One line, in the user's language: what running this does. */
    public String effect = "";
    public long createdAt;
    public long updatedAt;
    /** Zero until it has been run from the automation page. */
    public long lastRunAt;
    public boolean lastRunOk;
    /** A short summary of the last run: the return value, or the error. */
    public String lastOutcome = "";

    public static SavedScript fromJson(JSONObject o) {
        SavedScript s = new SavedScript();
        s.id = o.optString("id", "");
        s.name = o.optString("name", "");
        s.packageName = o.optString("packageName", "");
        s.source = o.optString("source", "");
        s.effect = o.optString("effect", "");
        s.createdAt = o.optLong("createdAt", 0L);
        s.updatedAt = o.optLong("updatedAt", 0L);
        s.lastRunAt = o.optLong("lastRunAt", 0L);
        s.lastRunOk = o.optBoolean("lastRunOk", false);
        s.lastOutcome = o.optString("lastOutcome", "");
        return s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("packageName", packageName);
        o.put("source", source);
        o.put("effect", effect);
        o.put("createdAt", createdAt);
        o.put("updatedAt", updatedAt);
        o.put("lastRunAt", lastRunAt);
        o.put("lastRunOk", lastRunOk);
        o.put("lastOutcome", lastOutcome);
        return o;
    }

    /** The metadata the agent sees when listing - everything but the source. */
    public JSONObject describe() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("package", packageName);
        o.put("effect", effect);
        o.put("createdAt", createdAt);
        o.put("updatedAt", updatedAt);
        o.put("lastRunAt", lastRunAt == 0 ? JSONObject.NULL : lastRunAt);
        o.put("lastRunOk", lastRunAt == 0 ? JSONObject.NULL : lastRunOk);
        o.put("lastOutcome", lastOutcome);
        return o;
    }
}
