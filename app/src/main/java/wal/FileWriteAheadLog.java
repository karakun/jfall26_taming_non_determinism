package wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * File-based Write-Ahead-Log using JSON Lines (JSONL) format.
 *
 * <p>Each entry is stored as a single JSON line, making the file:
 * <ul>
 *   <li>Append-only (no complex locking needed)</li>
 *   <li>Streamable (process entries without loading entire file)</li>
 *   <li>Human-readable (inspect with standard tools)</li>
 *   <li>Grep-friendly (one entry per line)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Write operations are synchronized. Read operations can proceed concurrently.
 *
 * <h2>Usage</h2>
 * <pre>
 * FileWriteAheadLog wal = new FileWriteAheadLog(Path.of("wal-data/interactions.jsonl"));
 * wal.append(entry);
 * List&lt;WalEntry&gt; entries = wal.list();
 * Optional&lt;WalEntry&gt; entry = wal.findById("abc-123");
 * </pre>
 */
public class FileWriteAheadLog {

    private final Path walFile;
    private final ObjectMapper mapper;
    private final Object writeLock = new Object();

    public FileWriteAheadLog(Path walFile) {
        this.walFile = walFile;
        this.mapper = createObjectMapper();
        ensureParentDirectoryExists();
    }

    private ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    private void ensureParentDirectoryExists() {
        try {
            Path parent = walFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            System.err.println("Could not create WAL directory: " + e.getMessage());
        }
    }

    /**
     * Appends an entry to the WAL.
     *
     * <p>This operation MUST complete before the response is shown to the user,
     * ensuring durability even if the system crashes immediately after.
     *
     * @param entry the entry to append
     * @throws IOException if the write fails
     */
    public void append(WalEntry entry) throws IOException {
        synchronized (writeLock) {
            String json = mapper.writeValueAsString(entry);
            Files.writeString(
                    walFile,
                    json + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }
    }

    /**
     * Lists all entries in the WAL, newest first.
     *
     * @return list of entries (newest first)
     */
    public List<WalEntry> list() {
        try {
            if (!Files.exists(walFile)) {
                return Collections.emptyList();
            }

            List<WalEntry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(walFile)) {
                if (!line.isBlank()) {
                    try {
                        entries.add(mapper.readValue(line, WalEntry.class));
                    } catch (IOException e) {
                        System.err.println("Failed to parse WAL line: " + e.getMessage());
                    }
                }
            }

            // Sort: security scan entries first, then by timestamp descending (newest first)
            entries.sort((a, b) -> {
                boolean aIsScan = isSecurityScanEntry(a);
                boolean bIsScan = isSecurityScanEntry(b);

                // Security scan entries come first
                if (aIsScan && !bIsScan) return -1;
                if (!aIsScan && bIsScan) return 1;

                // Within the same group, sort by timestamp descending
                return b.timestamp().compareTo(a.timestamp());
            });
            return entries;

        } catch (IOException e) {
            System.err.println("Failed to read WAL: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Lists the most recent N entries, newest first.
     *
     * @param limit maximum number of entries to return
     * @return list of entries (newest first)
     */
    public List<WalEntry> list(int limit) {
        List<WalEntry> all = list();
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(0, limit);
    }

    /**
     * Finds an entry by its ID.
     *
     * @param entryId the entry ID to find
     * @return the entry, or empty if not found
     */
    public Optional<WalEntry> findById(String entryId) {
        return list().stream()
                .filter(e -> entryId.equals(e.entryId()))
                .findFirst();
    }

    /**
     * Returns the total count of entries in the WAL.
     *
     * @return entry count
     */
    public long count() {
        try {
            if (!Files.exists(walFile)) {
                return 0;
            }
            return Files.lines(walFile)
                    .filter(line -> !line.isBlank())
                    .count();
        } catch (IOException e) {
            System.err.println("Failed to count WAL entries: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Checks if an entry is a security scan result.
     * Security scan entries have verdict BYPASSED or BLOCKED.
     */
    private boolean isSecurityScanEntry(WalEntry entry) {
        String verdict = entry.verdict();
        return "BYPASSED".equals(verdict) || "BLOCKED".equals(verdict);
    }

    /**
     * Returns the path to the WAL file.
     */
    public Path getWalFile() {
        return walFile;
    }
}
