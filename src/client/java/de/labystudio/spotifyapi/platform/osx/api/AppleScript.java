package de.labystudio.spotifyapi.platform.osx.api;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class AppleScript {
    private static final String GRAMMAR_FORMAT = "tell application \"%s\" to %s";
    private final String application;
    private final String[] runtimeParameters = {"osascript", "-e", null};
    private final Runtime runtime = Runtime.getRuntime();

    public AppleScript(String application) {
        this.application = application;
    }

    public String getOf(Action request, Action of) throws Exception {
        return execute(Action.GET, request, Action.OF, of);
    }

    public String get(Action request) throws Exception {
        return execute(Action.GET, request);
    }

    public String execute(Action... actions) throws Exception {
        String action = Action.toString(actions);
        this.runtimeParameters[2] = String.format(GRAMMAR_FORMAT, this.application, action);
        Process process = this.runtime.exec(this.runtimeParameters);
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder builder = new StringBuilder();
            while (true) {
                String line = reader.readLine();
                if (line != null) {
                    builder.append(line);
                } else {
                    return builder.toString();
                }
            }
        } else {
            BufferedReader reader2 = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder builder2 = new StringBuilder();
            while (true) {
                String line2 = reader2.readLine();
                if (line2 == null) {
                    break;
                }
                builder2.append(line2);
            }
            throw new Exception("AppleScript execution \"" + action + "\" failed with exit code " + exitCode + ": " + ((Object) builder2));
        }
    }
}
