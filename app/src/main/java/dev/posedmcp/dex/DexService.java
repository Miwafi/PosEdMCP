package dev.posedmcp.dex;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.posedmcp.Logx;

/**
 * Runs the analysis engines in their own process.
 *
 * <p>Declared as {@code android:process=":dex"} with a large heap. Parsing a
 * large APK allocates heavily and can fail in ways nothing can catch, and this
 * process is the one holding the MCP endpoint that a person is watching a
 * confirmation dialog on. Keeping the two apart means a decompiler that runs out
 * of memory costs a retry, not the whole server.
 *
 * <p>Requests arrive over Binder as a {@code Messenger} message, so no AIDL. The
 * JSON payload travels as a string and large results go to files, because a
 * Binder transaction is capped at about a megabyte and decompiled output is
 * routinely bigger than that.
 */
public class DexService extends Service {

    public static final String CLASS = "dev.posedmcp.dex.DexService";
    public static final int MSG_RUN = 1;
    public static final int MSG_RESULT = 2;

    public static final String KEY_ID = "id";
    public static final String KEY_OP = "op";
    public static final String KEY_ARGS = "args";
    public static final String KEY_OK = "ok";
    public static final String KEY_JSON = "json";
    public static final String KEY_ERROR = "error";

    /** Working area for outputs. Shared with the app process, which reads them. */
    public static File workDir(android.content.Context context) {
        File dir = new File(context.getCacheDir(), "dex");
        if (!dir.exists() && !dir.mkdirs()) {
            Logx.w("could not create " + dir);
        }
        return dir;
    }

    private Messenger messenger;
    private ExecutorService workers;

    @Override
    public void onCreate() {
        super.onCreate();
        // Serialised on purpose: two concurrent decompilations on one device
        // contend for memory, and the second one is the one that gets killed.
        workers = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "posedmcp-dex");
            t.setDaemon(true);
            return t;
        });

        messenger = new Messenger(new Handler(getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what != MSG_RUN) {
                    super.handleMessage(msg);
                    return;
                }
                final long id = msg.getData().getLong(KEY_ID, -1L);
                final String op = msg.getData().getString(KEY_OP, "");
                final String rawArgs = msg.getData().getString(KEY_ARGS, "{}");
                final Messenger replyTo = msg.replyTo;
                if (id < 0 || replyTo == null) {
                    return;
                }
                workers.submit(() -> run(id, op, rawArgs, replyTo));
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        if (workers != null) {
            workers.shutdownNow();
        }
        super.onDestroy();
    }

    private void run(long id, String op, String rawArgs, Messenger replyTo) {
        Bundle data = new Bundle();
        data.putLong(KEY_ID, id);
        try {
            JSONObject args = new JSONObject(rawArgs);
            JSONObject result = dispatch(op, args);
            data.putBoolean(KEY_OK, true);
            data.putString(KEY_JSON, result.toString());
        } catch (Throwable t) {
            Logx.e("dex op '" + op + "' failed", t);
            data.putBoolean(KEY_OK, false);
            data.putString(KEY_ERROR, t.getMessage() == null ? t.toString() : t.getMessage());
        }

        Message reply = Message.obtain(null, MSG_RESULT);
        reply.setData(data);
        try {
            replyTo.send(reply);
        } catch (RemoteException e) {
            Logx.w("could not deliver dex result: " + e);
        }
    }

    private JSONObject dispatch(String op, JSONObject args) throws Exception {
        switch (op) {
            case "classes": {
                File source = sourceFile(args);
                return DexQuery.listClasses(source, args.optString("filter", ""),
                        args.optInt("limit", 200), args.optBoolean("with_members", false));
            }
            case "search": {
                File source = sourceFile(args);
                return DexQuery.search(source, args.optString("pattern", ""),
                        args.optString("kind", "string"), args.optInt("limit", 100));
            }
            case "disassemble": {
                File source = sourceFile(args);
                File out = newWorkDir("smali");
                return SmaliTool.disassemble(source, out, args.optString("filter", ""), apiLevel());
            }
            case "assemble":
                return assemble(args);
            default:
                throw new IllegalArgumentException("unknown dex op '" + op + "'");
        }
    }

    /**
     * Accepts smali either as a directory already on disk or as inline text.
     *
     * <p>Inline matters: the agent usually has the smali in its head, not on the
     * filesystem, and making it write files first would just add a step.
     */
    private JSONObject assemble(JSONObject args) throws Exception {
        File working = newWorkDir("assemble");
        java.util.List<String> inputs = new java.util.ArrayList<>();

        String dir = args.optString("dir", "");
        if (!dir.isEmpty()) {
            File source = new File(dir);
            if (!source.isDirectory()) {
                throw new IllegalArgumentException("not a directory: " + dir);
            }
            inputs.add(source.getAbsolutePath());
        }

        org.json.JSONArray sources = args.optJSONArray("sources");
        if (sources != null) {
            File inline = new File(working, "inline");
            if (!inline.mkdirs() && !inline.isDirectory()) {
                throw new IllegalStateException("could not create " + inline);
            }
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.optJSONObject(i);
                if (source == null) {
                    continue;
                }
                String relative = source.optString("path", "");
                String content = source.optString("content", "");
                if (relative.isEmpty() || content.isEmpty()) {
                    continue;
                }
                File target = new File(inline, relative);
                if (!target.getCanonicalPath().startsWith(inline.getCanonicalPath())) {
                    throw new IllegalArgumentException("path escapes the working directory: " + relative);
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("could not create " + parent);
                }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                    out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            inputs.add(inline.getAbsolutePath());
        }

        if (inputs.isEmpty()) {
            throw new IllegalArgumentException(
                    "nothing to assemble: pass 'dir', or 'sources' as [{path, content}]");
        }

        String outName = args.optString("output", "classes.dex");
        File output = new File(working, outName.endsWith(".dex") ? outName : outName + ".dex");
        return SmaliTool.assemble(inputs, output, apiLevel());
    }

    private File newWorkDir(String prefix) {
        File dir = new File(workDir(this), prefix + "-" + System.currentTimeMillis());
        if (!dir.exists() && !dir.mkdirs()) {
            Logx.w("could not create " + dir);
        }
        return dir;
    }

    private int apiLevel() {
        return android.os.Build.VERSION.SDK_INT;
    }

    private File sourceFile(JSONObject args) throws Exception {
        String path = args.optString("path", "");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException("no such file: " + path);
        }
        if (!file.canRead()) {
            throw new IllegalArgumentException("cannot read: " + path);
        }
        return file;
    }
}
