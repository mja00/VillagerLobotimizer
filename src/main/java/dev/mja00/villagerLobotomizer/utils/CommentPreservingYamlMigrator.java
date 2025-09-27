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
     * Merges existing config with default config, preserving comments from both.
     * User comments are preserved, and comments from default config are added for new fields.
     */
    public String mergeWithComments(String existingYaml, String defaultYaml) throws IOException {
        try {
            logger.fine("Starting comment-preserving YAML merge");

            // Parse both YAML files with comment information
            CommentedYamlData existingData = parseWithComments(existingYaml);
            CommentedYamlData defaultData = parseWithComments(defaultYaml);

            logger.fine("Extracted " + existingData.comments.size() + " comments from existing config");
            logger.fine("Extracted " + defaultData.comments.size() + " comments from default config");

            // Parse actual data structures
            Yaml yaml = new Yaml();
            Map<String, Object> existingMap = yaml.load(existingYaml);
            Map<String, Object> defaultMap = yaml.load(defaultYaml);

            if (existingMap == null) existingMap = new LinkedHashMap<>();
            if (defaultMap == null) defaultMap = new LinkedHashMap<>();

            // Create merged data structure
            Map<String, Object> mergedMap = new LinkedHashMap<>();
            Map<String, String> mergedComments = new LinkedHashMap<>();

            // Merge the configurations
            mergeRecursive("", existingMap, defaultMap, mergedMap, existingData.comments, defaultData.comments, mergedComments);

            logger.fine("Merged configuration has " + mergedComments.size() + " comments");

            // Generate the final YAML with preserved comments
            return generateYamlWithComments(mergedMap, mergedComments, defaultData.headerComments);

        } catch (Exception e) {
            logger.severe("Error during comment-preserving merge:");
            logger.severe("  Error: " + e.getMessage());
            logger.severe("  Existing YAML length: " + existingYaml.length());
            logger.severe("  Default YAML length: " + defaultYaml.length());
            throw new IOException("Comment-preserving merge failed", e);
        }
    }

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
                // Recursive merge for nested maps
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
                    // Preserve existing comment
                    String comment = existingComments.get(keyPath);
                    if (comment != null) {
                        mergedComments.put(keyPath, comment);
                    }
                } else {
                    merged.put(key, defaultValue);
                    // Use default comment for new field
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
                // Preserve comment for obsolete key
                String comment = existingComments.get(keyPath);
                if (comment != null) {
                    mergedComments.put(keyPath, comment);
                }
                logger.info("Preserved obsolete config option: " + keyPath + " (consider removing)");
            }
        }
    }

    private CommentedYamlData parseWithComments(String yamlContent) {
        Map<String, String> comments = new LinkedHashMap<>();
        List<String> headerComments = new ArrayList<>();

        String[] lines = yamlContent.split("\\n");
        List<String> pendingComments = new ArrayList<>();
        boolean foundFirstKey = false;

        for (String line : lines) {
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

                // This is a key line, associate pending comments with it
                String key = extractKey(line);
                if (key != null && !pendingComments.isEmpty()) {
                    // Combine all pending comments
                    String combinedComment = String.join(" ", pendingComments);
                    comments.put(key, combinedComment);
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

    private String findCommentTarget(String[] lines, int commentIndex) {
        // Look for the next non-comment, non-empty line that contains a key
        for (int i = commentIndex + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#") && line.contains(":")) {
                // Skip lines that are just list items
                if (line.startsWith("-")) {
                    continue;
                }
                return extractKeyPath(lines, i);
            }
        }
        return null;
    }

    private String extractKeyPath(String[] lines, int lineIndex) {
        Stack<String> pathStack = new Stack<>();
        Stack<Integer> indentStack = new Stack<>();

        // Build path up to this line
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

    private String extractKey(String line) {
        String trimmed = line.trim();
        if (trimmed.contains(":")) {
            String key = trimmed.split(":")[0].trim();
            // Remove quotes if present
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            } else if (key.startsWith("'") && key.endsWith("'")) {
                key = key.substring(1, key.length() - 1);
            }
            return key;
        }
        return null;
    }

    private String generateYamlWithComments(Map<String, Object> data, Map<String, String> comments, List<String> headerComments) {
        StringBuilder result = new StringBuilder();

        // Add header comments
        for (String comment : headerComments) {
            result.append("#").append(comment).append("\n");
        }

        if (!headerComments.isEmpty()) {
            result.append("\n");
        }

        // Generate YAML with comments
        generateYamlSection(data, comments, result, "", 0);

        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private void generateYamlSection(Map<String, Object> data, Map<String, String> comments,
                                   StringBuilder result, String pathPrefix, int indentLevel) {
        String indent = "  ".repeat(indentLevel);

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String keyPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;

            // Add comment if exists
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