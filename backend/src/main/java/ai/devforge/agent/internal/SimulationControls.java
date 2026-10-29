package ai.devforge.agent.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Switches embedded in the input text, e.g. {@code [sim:investigate=timeout-once]}, that make the SIMULATION fail in specific ways so
 * every workflow path can be demonstrated and tested. They are only read by the simulated agent; real adapters ignore them.
 */
record SimulationControls(String investigate, boolean needsInfo, String validate, long slowMillis) {
    private static final Pattern P = Pattern.compile("\\[sim:([a-z]+)=([a-z0-9-]+)]");

    static SimulationControls parse(String text) {
        String inv = null, val = null;
        boolean info = false;
        long slow = 0;
        Matcher m = P.matcher(text == null ? "" : text);
        while (m.find()) {
            switch (m.group(1)) {
                case "investigate" -> inv = m.group(2);
                case "info" -> info = m.group(2).equals("needed");
                case "validate" -> val = m.group(2);
                case "slow" -> slow = Math.min(Long.parseLong(m.group(2)), 10_000);
                default -> { }
            }
        }
        return new SimulationControls(inv, info, val, slow);
    }
}
