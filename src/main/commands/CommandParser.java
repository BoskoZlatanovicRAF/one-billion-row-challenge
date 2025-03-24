package main.commands;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;

public class CommandParser {

    public Map.Entry<String, Map<String, String>> parseCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String commandName = parts[0];
        Map<String, String> args = new HashMap<>();

        if (parts.length > 1) {
            String argsString = parts[1];
            String[] argsParts = argsString.split("\\s+");

            for (int i = 0; i < argsParts.length; i++) {
                if (argsParts[i].startsWith("--") || argsParts[i].startsWith("-")) {
                    String key = argsParts[i];
                    String value = (i + 1 < argsParts.length && !argsParts[i + 1].startsWith("-"))
                            ? argsParts[++i] : "true";
                    args.put(key, value);
                }
            }
        }

        return new AbstractMap.SimpleEntry<>(commandName, args);
    }
}