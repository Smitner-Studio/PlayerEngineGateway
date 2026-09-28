package com.player2.playerengine.program;

import java.util.ArrayList;
import java.util.List;

/**
 * What an ended job hands back to the model (the results loop): every query's value, the program's
 * return value, the completion report and the error, from the calls log only, so the model reads
 * what the world showed and nothing else.
 */
public final class JobResults {
    /** The whole text is cut to this, so a results turn fits the deferred-context budget of a turn. */
    public static final int MAX_CHARS = 1800;
    private static final int MAX_VALUE_CHARS = 400;

    private JobResults() {
    }

    /** Whether the job made only queries (and said lines): nothing it did changed the world. */
    public static boolean queryOnly(Job job) {
        for (CallLog.Entry e : job.log().entries()) {
            if (!e.query() && !e.name().equals("say")) {
                return false;
            }
        }
        return true;
    }

    public static String render(Job job) {
        StringBuilder sb = new StringBuilder();
        sb.append("Job \"").append(job.goal()).append("\" ended: ").append(job.state().name().toLowerCase(
                java.util.Locale.ROOT)).append('.');
        List<String> found = new ArrayList<>();
        for (CallLog.Entry e : job.log().entries()) {
            if (e.query() && e.status() == CallLog.Status.DONE) {
                found.add(e.describe() + " = " + (e.ok() ? cut(Values.display(Values.fromHost(e.value())))
                        : e.error().toLine()));
            }
        }
        if (!found.isEmpty()) {
            sb.append(" Found: ").append(String.join("; ", found)).append('.');
        }
        if (job.result() != null) {
            sb.append(" Returned: ").append(cut(Values.display(job.result()))).append('.');
        }
        if (!queryOnly(job)) {
            sb.append(" Did: ").append(job.report());
        }
        if (job.lastError() != null) {
            sb.append(" Error: ").append(job.lastError().toLine()).append('.');
        }
        String s = sb.toString();
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS - 3) + "...";
    }

    private static String cut(String s) {
        return s.length() <= MAX_VALUE_CHARS ? s : s.substring(0, MAX_VALUE_CHARS - 3) + "...";
    }
}
