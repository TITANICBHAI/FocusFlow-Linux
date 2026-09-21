package com.focusflow.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Minimal privileged entry point for Linux hosts-file changes.
 *
 * This is intentionally Java-only so the helper can be launched with the
 * application jar alone; it does not inherit the development classpath or
 * require Kotlin/runtime dependencies. It accepts only fixed operations and
 * validated domains, and always targets /etc/hosts.
 */
public final class HostsPrivilegedHelper {
    private static final Path HOSTS_PATH = Path.of("/etc/hosts");
    private static final String MARKER = "# FocusFlow";
    private static final List<String> SUBDOMAINS =
            List.of("", "www.", "m.", "mobile.", "app.");
    private static final Pattern SAFE_DOMAIN = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$"
    );

    private HostsPrivilegedHelper() {}

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                throw new IllegalArgumentException("Linux only");
            }
            if (!"root".equals(System.getProperty("user.name"))) {
                throw new IllegalArgumentException("root privileges required");
            }
            if (args.length == 2 && ("block".equals(args[0]) || "unblock".equals(args[0]))) {
                String domain = normalizeDomain(args[1]);
                if (domain == null) throw new IllegalArgumentException("invalid domain");
                updateDomain(domain, "block".equals(args[0]));
            } else if (args.length == 1 && "unblock-all".equals(args[0])) {
                removeAll();
            } else {
                throw new IllegalArgumentException("unsupported operation");
            }
            System.out.println("ok");
        } catch (Exception e) {
            System.err.println(e.getMessage() == null ? "hosts helper failed" : e.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static void updateDomain(String domain, boolean add) throws IOException {
        Path target = checkedTarget();
        String existing = normalizeContent(Files.readString(target, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>(Arrays.asList(existing.split("\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        Set<String> entries = new HashSet<>();
        for (String prefix : SUBDOMAINS) {
            entries.add("127.0.0.1  " + prefix + domain + "  " + MARKER);
        }

        List<String> updated = new ArrayList<>();
        if (add) {
            updated.addAll(lines);
            for (String entry : entries) {
                boolean present = false;
                for (String line : updated) {
                    if (line.trim().equals(entry)) {
                        present = true;
                        break;
                    }
                }
                if (!present) updated.add(entry);
            }
        } else {
            for (String line : lines) {
                if (!entries.contains(line.trim())) updated.add(line);
            }
        }
        atomicReplace(target, String.join("\n", updated) + "\n");
    }

    private static void removeAll() throws IOException {
        Path target = checkedTarget();
        String existing = normalizeContent(Files.readString(target, StandardCharsets.UTF_8));
        List<String> updated = new ArrayList<>();
        for (String line : existing.split("\\n", -1)) {
            if (!line.contains(MARKER)) updated.add(line);
        }
        String content = String.join("\n", updated);
        if (!content.endsWith("\n")) content += "\n";
        atomicReplace(target, content);
    }

    private static Path checkedTarget() {
        if (!Files.isRegularFile(HOSTS_PATH, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("hosts path is not a regular file");
        }
        return HOSTS_PATH;
    }

    private static void atomicReplace(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IllegalStateException("hosts path has no parent");
        Set<PosixFilePermission> originalPermissions = null;
        try {
            originalPermissions = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException ignored) {
            // Linux hosts files support POSIX permissions; keep this defensive
            // fallback for unusual test filesystems.
        }

        Path temp = Files.createTempFile(parent, ".focusflow-hosts-", ".tmp");
        try {
            Files.writeString(
                    temp,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            if (originalPermissions != null) {
                Files.setPosixFilePermissions(temp, originalPermissions);
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String normalizeDomain(String domain) {
        String root = domain.trim().toLowerCase(Locale.ROOT);
        if (root.startsWith("www.")) root = root.substring(4);
        return root.length() <= 253 && !root.isEmpty() && SAFE_DOMAIN.matcher(root).matches()
                ? root : null;
    }

    private static String normalizeContent(String content) {
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }
}