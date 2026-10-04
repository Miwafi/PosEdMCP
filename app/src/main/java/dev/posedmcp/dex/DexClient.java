package dev.posedmcp.dex;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.posedmcp.Logx;

/**
 * App-side handle on the isolated analysis process.
 *
 * <p>Binds lazily and stays bound: the whole point of the separate process is
 * that it can be killed by the platform under memory pressure, so every call
 * re-checks the binding and rebinds if it went away.
 */
public final class DexClient {

    private static volatile DexClient instance;

    private final Context context;
    private final AtomicLong ids = new AtomicLong(1L);
    private final Map<Long, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final HandlerThread replyThread;

    private volatile Messenger service;
    private volatile boolean binding;

    private DexClient(Context context) {
        this.context = context.getApplicationContext();
        // Replies must not land on the main thread: the tool call that is
        // waiting for them may itself have been started from there.
        this.replyThread = new HandlerThread("posedmcp-dex-reply");
        this.replyThread.start();
    }

    public static DexClient get(Context context) {
        DexClient local = instance;
        if (local == null) {
            synchronized (DexClient.class) {
                local = instance;
                if (local == null) {
                    local = new DexClient(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Sends one op and waits for its result. */
    public JSONObject call(String op, JSONObject args, long timeoutMs) throws IOException {
        Messenger target = ensureBound(timeoutMs);
        if (target == null) {
            throw new IOException("the analysis process did not start");
        }

        long id = ids.getAndIncrement();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(id, future);

        Bundle data = new Bundle();
        data.putLong(DexService.KEY_ID, id);
        data.putString(DexService.KEY_OP, op);
        data.putString(DexService.KEY_ARGS, args == null ? "{}" : args.toString());

        Message request = Message.obtain(null, DexService.MSG_RUN);
        request.setData(data);
        request.replyTo = new Messenger(new Handler(replyThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what != DexService.MSG_RESULT) {
                    return;
                }
                Bundle reply = msg.getData();
                CompletableFuture<JSONObject> waiting = pending.remove(reply.getLong(DexService.KEY_ID));
                if (waiting == null) {
                    return;
                }
                try {
                    if (reply.getBoolean(DexService.KEY_OK, false)) {
                        waiting.complete(new JSONObject(reply.getString(DexService.KEY_JSON, "{}")));
                    } else {
                        waiting.completeExceptionally(
                                new IOException(reply.getString(DexService.KEY_ERROR, "analysis failed")));
                    }
                } catch (Throwable t) {
                    waiting.completeExceptionally(t);
                }
            }
        });

        try {
            target.send(request);
        } catch (Throwable t) {
            pending.remove(id);
            service = null;
            throw new IOException("could not reach the analysis process: " + t);
        }

        try {
            return future.get(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(id);
            throw new IOException("the analysis process timed out after " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new IOException("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(id);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    private Messenger ensureBound(long timeoutMs) {
        Messenger current = service;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (service != null) {
                return service;
            }
            if (!binding) {
                binding = true;
                CountDownLatch connected = new CountDownLatch(1);
                ServiceConnection connection = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder binder) {
                        service = new Messenger(binder);
                        binding = false;
                        connected.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        service = null;
                        binding = false;
                    }
                };
                boolean requested = false;
                try {
                    requested = context.bindService(
                            new Intent().setComponent(
                                    new ComponentName(context.getPackageName(), DexService.CLASS)),
                            connection, Context.BIND_AUTO_CREATE);
                } catch (Throwable t) {
                    Logx.e("could not bind the analysis process", t);
                }
                binding = false;
                if (!requested) {
                    return null;
                }
                try {
                    connected.await(Math.max(500L, timeoutMs / 2), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return service;
        }
    }
}
