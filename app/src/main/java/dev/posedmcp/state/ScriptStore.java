package dev.posedmcp.state;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The library of scripts the user chose to keep.
 *
 * <p>One JSON blob rather than a database: the set is small, it is read whole
 * whenever the automation page redraws, and real storage machinery would be more
 * than the data deserves.
 */
public final class ScriptStore {

    private static final String FILE = "scripts";
    private static final String KEY = "saved";

    private final SharedPreferences sp;

    private ScriptStore(SharedPreferences sp) {
        this.sp = sp;
    }

    public static ScriptStore of(Context ctx) {
        return new ScriptStore(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    /** Newest first, which is the order the automation page shows them in. */
    public synchronized List<SavedScript> all() {
        List<SavedScript> scripts = new ArrayList<>(read());
        Collections.sort(scripts, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return scripts;
    }

    public synchronized SavedScript byId(String id) {
        for (SavedScript script : read()) {
            if (script.id.equals(id)) {
                return script;
            }
        }
        return null;
    }

    public synchronized SavedScript byName(String name) {
        for (SavedScript script : read()) {
            if (script.name.equals(name)) {
                return script;
            }
        }
        return null;
    }

    /**
     * Creates the script, or replaces the one already carrying that name.
     *
     * <p>Replacing rather than accumulating is deliberate: writing a script,
     * correcting it and writing it again is the normal loop, and a library that
     * grew a near-duplicate each time would be unusable.
     *
     * <p>What the last run did is carried over - it describes the script, not the
     * edit - and the page shows it until the next run replaces it.
     */
    public synchronized SavedScript save(String name, String packageName, String source,
            String effect) {
        List<SavedScript> scripts = read();
        long now = System.currentTimeMillis();
        for (SavedScript existing : scripts) {
            if (existing.name.equals(name)) {
                // A different script wearing the same name. The recorded run
                // belonged to the old one, and leaving it there would show the
                // user a failure that the script in front of them no longer has.
                if (!existing.source.equals(source)) {
                    existing.lastRunAt = 0;
                    existing.lastRunOk = false;
                    existing.lastOutcome = "";
                }
                existing.packageName = packageName;
                existing.source = source;
                existing.effect = effect;
                existing.updatedAt = now;
                write(scripts);
                return existing;
            }
        }
        SavedScript script = new SavedScript();
        script.id = UUID.randomUUID().toString();
        script.name = name;
        script.packageName = packageName;
        script.source = source;
        script.effect = effect;
        script.createdAt = now;
        script.updatedAt = now;
        scripts.add(script);
        write(scripts);
        return script;
    }

    public synchronized boolean delete(String id) {
        List<SavedScript> scripts = read();
        for (int i = 0; i < scripts.size(); i++) {
            if (scripts.get(i).id.equals(id)) {
                scripts.remove(i);
                write(scripts);
                return true;
            }
        }
        return false;
    }

    /** Records what happened the last time the user ran this script. */
    public synchronized void recordRun(String id, boolean ok, String outcome) {
        List<SavedScript> scripts = read();
        for (SavedScript script : scripts) {
            if (script.id.equals(id)) {
                script.lastRunAt = System.currentTimeMillis();
                script.lastRunOk = ok;
                script.lastOutcome = outcome == null ? "" : outcome;
                write(scripts);
                return;
            }
        }
    }

    private List<SavedScript> read() {
        List<SavedScript> scripts = new ArrayList<>();
        String blob = sp.getString(KEY, "");
        if (blob == null || blob.isEmpty()) {
            return scripts;
        }
        try {
            JSONArray array = new JSONArray(blob);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) {
                    scripts.add(SavedScript.fromJson(o));
                }
            }
        } catch (Throwable ignored) {
            // Nothing here is irreplaceable, and refusing to start is worse than
            // starting with an empty library.
        }
        return scripts;
    }

    private void write(List<SavedScript> scripts) {
        JSONArray array = new JSONArray();
        for (SavedScript script : scripts) {
            try {
                array.put(script.toJson());
            } catch (Throwable ignored) {
            }
        }
        // commit(), not apply(): the automation page may draw again immediately,
        // and a save that has not reached disk yet would read back as missing.
        sp.edit().putString(KEY, array.toString()).commit();
    }
}
