package siliconshaders.packprobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only shader ZIP access. Never extracts an archive into the game or changes its contents. */
public final class PackArchive implements AutoCloseable {
    private static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EXPANDED_CHARS = 16 * 1024 * 1024;
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+[\"<]([^\">]+)[\">]\\s*(?://.*)?$");
    private final ZipFile zip;
    private final Map<String, Integer> sources = new LinkedHashMap<>();

    public PackArchive(Path file) throws IOException {
        zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8);
    }

    public List<String> entries() {
        return zip.stream().filter(e -> !e.isDirectory()).map(ZipEntry::getName).sorted().toList();
    }

    public String read(String path) throws IOException {
        return new String(readBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\uFEFF", "");
    }

    public byte[] readBytes(String path) throws IOException {
        String name = normalize(path);
        ZipEntry entry = zip.getEntry("shaders/" + name);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing shader source: " + name);
        try (var in = zip.getInputStream(entry)) {
            byte[] data = in.readNBytes(MAX_SOURCE_BYTES + 1);
            if (data.length > MAX_SOURCE_BYTES) throw new IOException("Shader source exceeds limit: " + name);
            return data;
        }
    }

    public Map<String, Integer> sources() { return Map.copyOf(sources); }

    public String expand(String path, Map<String, String> options) throws IOException {
        sources.clear();
        StringBuilder out = new StringBuilder();
        expand(normalize(path), options, new ArrayDeque<>(), out);
        return out.toString();
    }

    private void expand(String path, Map<String, String> options, ArrayDeque<String> stack, StringBuilder out) throws IOException {
        if (stack.contains(path)) throw new IOException("Include cycle: " + stack + " -> " + path);
        if (stack.size() >= 64) throw new IOException("Include nesting exceeds 64: " + path);
        stack.addLast(path);
        int id = sources.computeIfAbsent(path, k -> sources.size() + 1);
        boolean inComment = false;
        String[] lines = read(path).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            StringBuilder visible = new StringBuilder();
            for (int c = 0; c < line.length();) {
                if (inComment) {
                    int end = line.indexOf("*/", c);
                    if (end < 0) break;
                    inComment = false;
                    c = end + 2;
                    visible.append(' ');
                } else if (line.startsWith("/*", c)) {
                    inComment = true;
                    c += 2;
                } else if (line.startsWith("//", c)) {
                    break;
                } else visible.append(line.charAt(c++));
            }
            var include = INCLUDE.matcher(visible);
            if (include.matches()) {
                String child = include.group(1);
                if (!child.startsWith("/")) {
                    int slash = path.lastIndexOf('/');
                    child = (slash < 0 ? "" : path.substring(0, slash + 1)) + child;
                }
                child = normalize(child);
                int childId = sources.computeIfAbsent(child, k -> sources.size() + 1);
                out.append("#line 1 ").append(childId).append('\n');
                expand(child, options, stack, out);
                out.append("\n#line ").append(i + 2).append(' ').append(id).append('\n');
            } else {
                // Only numeric #define options are supported by this compiler probe. No pack file is edited.
                var define = Pattern.compile("^(\\s*#\\s*define\\s+)([A-Za-z_]\\w*)(\\s+)([^/]*)(.*)$").matcher(line);
                if (!inComment && define.matches() && options.containsKey(define.group(2))) {
                    String value = options.get(define.group(2));
                    if (!value.matches("-?\\d+(?:\\.\\d+)?")) throw new IOException("Non-numeric option: " + define.group(2));
                    line = define.group(1) + define.group(2) + " " + value + " " + define.group(5);
                }
                out.append(line).append('\n');
            }
            if (out.length() > MAX_EXPANDED_CHARS) throw new IOException("Expanded shader exceeds limit");
        }
        stack.removeLast();
    }

    private static String normalize(String path) throws IOException {
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) throw new IOException("Invalid include path: " + path);
        var parts = new ArrayDeque<String>();
        for (String part : path.replaceFirst("^/", "").split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IOException("Include escapes shader root: " + path);
                parts.removeLast();
            } else parts.addLast(part);
        }
        if (parts.isEmpty()) throw new IOException("Empty shader path");
        return String.join("/", parts);
    }

    @Override public void close() throws IOException { zip.close(); }
}
