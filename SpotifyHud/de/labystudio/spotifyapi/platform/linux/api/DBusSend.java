package de.labystudio.spotifyapi.platform.linux.api;

import de.labystudio.spotifyapi.platform.linux.api.model.InterfaceMember;
import de.labystudio.spotifyapi.platform.linux.api.model.Parameter;
import de.labystudio.spotifyapi.platform.linux.api.model.Variant;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class DBusSend {
    private static final Parameter PARAM_PRINT_REPLY = new Parameter("print-reply");
    private static final InterfaceMember INTERFACE_GET = new InterfaceMember("org.freedesktop.DBus.Properties.Get");
    private final Parameter[] parameters;
    private final String objectPath;
    private final Runtime runtime = Runtime.getRuntime();

    public DBusSend(Parameter[] parameters, String objectPath) {
        this.parameters = parameters;
        this.objectPath = objectPath;
    }

    public Variant get(String... keys) throws Exception {
        String[] contents = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            contents[i] = String.format("string:%s", keys[i]);
        }
        return send(INTERFACE_GET, contents);
    }

    public Variant send(InterfaceMember interfaceMember, String... contents) throws Exception {
        String[] arguments = new String[2 + this.parameters.length + 2 + contents.length];
        arguments[0] = "dbus-send";
        arguments[1] = PARAM_PRINT_REPLY.toString();
        for (int i = 0; i < this.parameters.length; i++) {
            arguments[2 + i] = this.parameters[i].toString();
        }
        arguments[2 + this.parameters.length] = this.objectPath;
        arguments[2 + this.parameters.length + 1] = interfaceMember.toString();
        for (int i2 = 0; i2 < contents.length; i2++) {
            arguments[2 + this.parameters.length + 2 + i2] = contents[i2];
        }
        Process process = this.runtime.exec(arguments);
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder builder = new StringBuilder();
            while (true) {
                String response = reader.readLine();
                if (response == null) {
                    break;
                }
                if (!response.startsWith("method ")) {
                    builder.append(response).append("\n");
                }
            }
            if (builder.toString().isEmpty()) {
                return new Variant("success", true);
            }
            return Variant.parse(builder.toString());
        }
        BufferedReader reader2 = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        StringBuilder builder2 = new StringBuilder();
        while (true) {
            String line = reader2.readLine();
            if (line == null) {
                break;
            }
            builder2.append(line);
        }
        throw new Exception("dbus-send execution \"" + String.join(" ", arguments) + "\" failed with exit code " + exitCode + ": " + ((Object) builder2));
    }
}
