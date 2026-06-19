package dev.mja00.villagerLobotomizer.utils;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A YAML migrator that preserves comments while merging configurations.
 * This allows user comments to be kept during config migrations while adding
 * comments from the default config for new fields.
 */
public class CommentPreservingYamlMigrator {
    private final Logger logger;
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^(\\s*)#(.*)$");

    public CommentPreservingYamlMigrator(Logger logger) {
        this.logger = logger;
    }

    /**
     * Merges default YAML configuration with existing YAML while preserving comments from both.
     *
     * <p>User comments from the existing YAML are preserved for matching keys. Comments from
     * the default YAML are added for new keys and sections not present in the existing YAML.
     * Extra keys from the existing YAML that are not in the default are preserved in the output.
     *
     * @param existingYaml the existing YAML configuration
     * @param defaultYaml  the default YAML configuration to merge
     * @return the merged YAML as a string with comments from both sources
     * @throws IOException if the merge process fails
     */
    public String mergeWithComments(String existingYaml, String defaultYaml) throws IOException {
        try {
            logger.fine("Starting comment-preserving YAML merge");

            CommentedYamlData existingData = parseWithComments(existingYaml);
            CommentedYamlData defaultData = parseWithComments(defaultYaml);

            logger.fine("Extracted " + existingData.comments.size() + " comments from existing config");
            logger.fine("Extracted " + defaultData.comments.size() + " comments from default config");

            Yaml yaml = new Yaml();
            Map<String, Object> existingMap = yaml.load(existingYaml);
            Map<String, Object> defaultMap = yaml.load(defaultYaml);

            if (existingMap == null) existingMap = new LinkedHashMap<>();
            if (defaultMap == null) defaultMap = new LinkedHashMap<>();

            Map<String, Object> mergedMap = new LinkedHashMap<>();
            Map<String, String> mergedComments = new LinkedHashMap<>();

            mergeRecursive("", existingMap, defaultMap, mergedMap, existingData.comments, defaultData.comments, mergedComments);

            logger.fine("Merged configuration has " + mergedComments.size() + " comments");

            return generateYamlWithComments(mergedMap, mergedComments, defaultData.headerComments);

        } catch (Exception e) {
            logger.severe("Error during comment-preserving merge:");
            logger.severe("  Error: " + e.getMessage());
            logger.severe("  Existing YAML length: " + existingYaml.length());
            logger.severe("  Default YAML length: " + defaultYaml.length());
            throw new IOException("Comment-preserving merge failed", e);
        }
    }

    /**
     * Recursively merges default YAML configuration into existing configuration, preserving comments and extra keys.
     */
    @SuppressWarnings("unchecked")
    private void mergeRecursive(String path, Map<String, Object> existing, Map<String, Object> defaults,
                               Map<String, Object> merged, Map<String, String> existingComments,
                               Map<String, String> defaultComments, Map<String, String> mergedComments) {

        // First, add all keys from defaults to maintain order
        for (String key : defaults.keySet()) {
            String keyPath = path.isEmpty() ? key : path + "." + key;
            Object defaultValue = defaults.get(key);
            Object existingValue = existing.get(key);

            if (defaultValue instanceof Map && existingValue instanceof Map) {
                Map<String, Object> nestedMerged = new LinkedHashMap<>();
                merged.put(key, nestedMerged);

                mergeRecursive(keyPath, (Map<String, Object>) existingValue,
                             (Map<String, Object>) defaultValue, nestedMerged,
                             existingComments, defaultComments, mergedComments);

                // Preserve existing comment or use default comment
                String comment = existingComments.get(keyPath);
                if (comment == null) {
                    comment = defaultComments.get(keyPath);
                    if (comment != null) {
                        logger.info("Added comment for new section: " + keyPath);
                    }
                }
                if (comment != null) {
                    mergedComments.put(keyPath, comment);
                }
            } else {
                // Use existing value if present, otherwise use default
                if (existingValue != null) {
                    merged.put(key, existingValue);
                    String comment = existingComments.get(keyPath);
                    if (comment != null) {
                        mergedComments.put(keyPath, comment);
                    }
                } else {
                    merged.put(key, defaultValue);
                    String comment = defaultComments.get(keyPath);
                    if (comment != null) {
                        mergedComments.put(keyPath, comment);
                        logger.info("Added new config option with comment: " + keyPath + " = " + defaultValue);
                    } else {
                        logger.info("Added new config option: " + keyPath + " = " + defaultValue);
                    }
                }
            }
        }

        // Add any extra keys from existing config that aren't in defaults
        for (String key : existing.keySet()) {
            if (!defaults.containsKey(key)) {
                String keyPath = path.isEmpty() ? key : path + "." + key;
                merged.put(key, existing.get(key));
                String comment = existingComments.get(keyPath);
                if (comment != null) {
                    mergedComments.put(keyPath, comment);
                }
                logger.info("Preserved obsolete config option: " + keyPath + " (consider removing)");
            }
        }
    }

    /**
     * Extracts comments from YAML content and associates them with keys.
     *
     * Comments appearing before the first detected key are treated as header comments.
     * Subsequent full-line comments are buffered and attached to the following key line,
     * identified by dot-separated paths based on indentation nesting.
     *
     * @param yamlContent the YAML content to parse
     * @return a CommentedYamlData object containing the mapping of dot-path keys to their comments
     *         and a list of header comments
     */
    private CommentedYamlData parseWithComments(String yamlContent) {
        Map<String, String> comments = new LinkedHashMap<>();
        List<String> headerComments = new ArrayList<>();

        String[] lines = yamlContent.split("\\n");
        List<String> pendingComments = new ArrayList<>();
        boolean foundFirstKey = false;

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            Matcher commentMatcher = COMMENT_PATTERN.matcher(line);

            if (commentMatcher.matches()) {
                String comment = commentMatcher.group(2).trim();

                if (!foundFirstKey) {
                    headerComments.add(comment);
                } else {
                    pendingComments.add(comment);
                }
            } else if (line.trim().contains(":") && !line.trim().startsWith("#") && !line.trim().startsWith("-")) {
                foundFirstKey = true;

                // Key line: attach pending comments to it
                if (!pendingComments.isEmpty()) {
                    String keyPath = extractKeyPath(lines, lineIndex);
                    if (keyPath == null || keyPath.isEmpty()) {
                        keyPath = extractKey(line);
                    }
                    String combinedComment = String.join(" ", pendingComments);
                    if (keyPath != null && !keyPath.isEmpty()) {
                        comments.put(keyPath, combinedComment);
                    }
                    pendingComments.clear();
                }
            } else if (line.trim().isEmpty()) {
                // Empty line, keep pending comments for next key
                continue;
            } else if (foundFirstKey && !line.trim().startsWith("-")) {
                // Non-key, non-comment line - clear pending comments
                pendingComments.clear();
            }
        }

        return new CommentedYamlData(comments, headerComments);
    }

    /**
     * Determines the dot-separated key path for a YAML line based on its nesting context.
     *
     * @param lines     the YAML content split into lines
     * @param lineIndex the index of the line for which to extract the key path
     * @return          the dot-separated key path representing the nested structure, or an empty string if no keys are found
     */
    private String extractKeyPath(String[] lines, int lineIndex) {
        Stack<String> pathStack = new Stack<>();
        Stack<Integer> indentStack = new Stack<>();

        for (int i = 0; i <= lineIndex; i++) {
            String line = lines[i];
            if (line.trim().contains(":") && !line.trim().startsWith("#")) {
                updatePath(line, pathStack, indentStack);
            }
        }

        return pathStack.isEmpty() ? "" : String.join(".", pathStack);
    }

    private void updatePath(String line, Stack<String> pathStack, Stack<Integer> indentStack) {
        int indent = getIndentLevel(line);
        String key = extractKey(line);

        if (key == null) return;

        // Pop elements until we find the correct parent level
        while (!indentStack.isEmpty() && indentStack.peek() >= indent) {
            indentStack.pop();
            if (!pathStack.isEmpty()) {
                pathStack.pop();
            }
        }

        pathStack.push(key);
        indentStack.push(indent);
    }

    private int getIndentLevel(String line) {
        int indent = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') indent++;
            else if (c == '\t') indent += 4; // Treat tab as 4 spaces
            else break;
        }
        return indent;
    }

    /**
     * Extracts the YAML key from a line, removing surrounding quotes.
     *
     * @param line the YAML line to parse
     * @return the key portion before the first colon, with surrounding quotes removed, or {@code null} if no colon is found
     */
    private String extractKey(String line) {
        String trimmed = line.trim();
        if (trimmed.contains(":")) {
            String key = trimmed.split(":")[0].trim();
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            } else if (key.startsWith("'") && key.endsWith("'")) {
                key = key.substring(1, key.length() - 1);
            }
            return key;
        }
        return null;
    }

    /**
     * Generates a YAML string with comments attached to keys.
     *
     * @param data the YAML data structure to serialize
     * @param comments mapping from dot-separated key paths to comment strings
     * @param headerComments comments to include at the start of the output
     * @return the formatted YAML string with comments
     */
    private String generateYamlWithComments(Map<String, Object> data, Map<String, String> comments, List<String> headerComments) {
        StringBuilder result = new StringBuilder();

        for (String comment : headerComments) {
            result.append("#").append(comment).append("\n");
        }

        if (!headerComments.isEmpty()) {
            result.append("\n");
        }

        generateYamlSection(data, comments, result, "", 0);

        return result.toString();
    }

    /**
     * Serializes a YAML section with attached comments at the specified indentation level.
     *
     * @param comments map of dot-separated key paths to comment strings
     * @param pathPrefix the current dot-separated key path prefix for looking up comments
     */
    @SuppressWarnings("unchecked")
    private void generateYamlSection(Map<String, Object> data, Map<String, String> comments,
                                   StringBuilder result, String pathPrefix, int indentLevel) {
        String indent = "  ".repeat(indentLevel);

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String keyPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;

            String comment = comments.get(keyPath);
            if (comment != null && !comment.isEmpty()) {
                result.append(indent).append("#").append(comment).append("\n");
            }

            if (value instanceof Map) {
                result.append(indent).append(key).append(":\n");
                generateYamlSection((Map<String, Object>) value, comments, result, keyPath, indentLevel + 1);
            } else if (value instanceof List<?> list) {
                result.append(indent).append(key).append(":\n");
                for (Object item : list) {
                    result.append(indent).append("  - ").append(formatValue(item)).append("\n");
                }
            } else {
                result.append(indent).append(key).append(": ").append(formatValue(value)).append("\n");
            }
        }
    }

    private String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String str) {
            // Quote strings that need quoting
            if (str.contains(":") || str.contains("#") || str.contains("\"") || str.trim().isEmpty()) {
                return "\"" + str.replace("\"", "\\\"") + "\"";
            }
            return str;
        }
        return value.toString();
    }

    private record CommentedYamlData(Map<String, String> comments, List<String> headerComments) {
    }
}
