package dev.posedmcp.state;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, sequence-numbered feed of events pushed by hooked processes.
 *
 * <p>Agents poll with a cursor so they never re-read or miss entries: each
 * event carries a monotonically increasing {@code seq}, and {@code since}
 * returns everything after it.
 */
public final class EventStore {

    public static final int CAPACITY = 512;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long nextSeq = 1L;
    private volatile Listener listener;

    /** Notified after every accepted event, so callers can push it to subscribers. */
    public interface Listener {
        void onEvent(Entry entry);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public static final class Entry {
        public final long seq;
        public final long timestamp;
        public final String source;
        public final String type;
        public final JSONObject data;

        Entry(long seq, long timestamp, String source, String type, JSONObject data) {
            this.seq = seq;
            this.timestamp = timestamp;
            this.source = source;
            this.type = type;
            this.data = data;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("seq", seq);
                o.put("ts", timestamp);
                o.put("source", source);
                o.put("type", type);
                o.put("data", data == null ? new JSONObject() : data);
            } catch (Throwable ignored) {
            }
            return o;
        }
    }

    public synchronized void add(String source, String type, JSONObject data, long timestamp) {
        Entry entry = new Entry(nextSeq++, timestamp, source, type, data);
        entries.addLast(entry);
        while (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
        Listener l = listener;
        if (l != null) {
            try {
                l.onEvent(entry);
            } catch (Throwable ignored) {
                // A subscriber must never break event ingestion.
            }
        }
    }

    public synchronized List<Entry> since(long seq, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq > seq) {
                out.add(e);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    public synchronized long lastSeq() {
        return nextSeq - 1;
    }

    public synchronized long oldestSeq() {
        Entry first = entries.peekFirst();
        return first == null ? nextSeq : first.seq;
    }
}
