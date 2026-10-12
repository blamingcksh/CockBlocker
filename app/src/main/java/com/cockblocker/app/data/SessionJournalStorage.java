package com.cockblocker.app.data;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Owns the four journal artifacts in credential-protected app storage. */
public final class SessionJournalStorage {
    /**
     * Single atomic write protocol for every artifact, independent of the host
     * platform's AtomicFile: write {@code <name>.new} beside the target, fsync,
     * then move it into place. Recovery reads only the canonical name, so a
     * leftover {@code .new} from a crash is discarded, never parsed.
     */
    private static final String TEMP_SUFFIX = ".new";
    public static final String SLOT_A = "session.slot.a";
    public static final String SLOT_B = "session.slot.b";
    public static final String HEAD = "session.head";
    public static final String BOOTSTRAP = "session.bootstrap";
    private static final int MAX_ARTIFACT_BYTES = SessionJournalCodec.MAX_PAYLOAD_BYTES + 128;

    private final File filesDir;

    public SessionJournalStorage(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        this.filesDir = context.getFilesDir();
        if (filesDir == null) {
            throw new IllegalStateException("filesDir unavailable");
        }
    }

    /**
     * Package-visible seam used by JVM tests; production always builds storage from
     * the app {@link Context#getFilesDir()}.
     */
    SessionJournalStorage(File filesDir) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir is required");
        }
        this.filesDir = filesDir;
    }

    public byte[] readSlotA() { return read(SLOT_A); }
    public byte[] readSlotB() { return read(SLOT_B); }
    public byte[] readHead() { return read(HEAD); }
    public byte[] readBootstrap() { return read(BOOTSTRAP); }

    public byte[] readSlot(boolean slotA) { return slotA ? readSlotA() : readSlotB(); }
    public byte[] readSlot(int slot) {
        if (slot != 0 && slot != 1) {
            throw new IllegalArgumentException("slot must be 0 or 1");
        }
        return readSlot(slot == 0);
    }

    public void writeSlot(boolean slotA, byte[] bytes) { write(slotA ? SLOT_A : SLOT_B, bytes); }
    public void writeSlot(int slot, byte[] bytes) {
        if (slot != 0 && slot != 1) {
            throw new IllegalArgumentException("slot must be 0 or 1");
        }
        writeSlot(slot == 0, bytes);
    }
    public void writeSlot(boolean slotA, SessionRecord record, JournalKey key) {
        writeSlot(slotA, SessionJournalCodec.encodeRecord(record, key));
    }
    public void writeSlot(int slot, SessionRecord record, JournalKey key) {
        if (slot != 0 && slot != 1) {
            throw new IllegalArgumentException("slot must be 0 or 1");
        }
        writeSlot(slot == 0, SessionJournalCodec.encodeRecord(record, key));
    }

    public void writeHead(SessionRecord record, JournalKey key) { writeHead(record, key, -1); }
    public void writeHead(SessionRecord record, JournalKey key, int slot) {
        write(HEAD, SessionJournalCodec.encodeHead(record, key, slot));
    }

    public void writeBootstrap(SessionRecord record, JournalKey key) {
        write(BOOTSTRAP, SessionJournalCodec.encodeBootstrap(record, key));
    }

    public boolean hasAnyArtifact() {
        return hasArtifact(SLOT_A) || hasArtifact(SLOT_B) || hasArtifact(HEAD) || hasArtifact(BOOTSTRAP);
    }

    public boolean hasBootstrap() { return hasArtifact(BOOTSTRAP); }

    /** Removes one named artifact. The four canonical names are accepted. */
    public boolean quarantine(String artifact) {
        String canonical = canonicalArtifact(artifact);
        return file(canonical).delete();
    }

    public int quarantine() {
        int removed = 0;
        for (String artifact : new String[] { SLOT_A, SLOT_B, HEAD, BOOTSTRAP }) {
            if (file(artifact).delete()) {
                removed++;
            }
        }
        return removed;
    }

    public File file(String artifact) {
        return new File(filesDir, canonicalArtifact(artifact));
    }

    private void write(String artifact, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("invalid journal artifact");
        }
        Path target = file(artifact).toPath();
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // The original write failure is the useful failure.
            }
            throw new IllegalStateException("journal write failed", failure);
        }
    }

    private boolean hasArtifact(String artifact) {
        File target = file(artifact);
        return target.isFile() && target.length() > 0 && target.length() <= MAX_ARTIFACT_BYTES;
    }

    private byte[] read(String artifact) {
        Path target = file(artifact).toPath();
        if (Files.isRegularFile(target)) {
            try {
                long size = Files.size(target);
                if (size <= 0 || size > MAX_ARTIFACT_BYTES) {
                    return null;
                }
                byte[] bytes = Files.readAllBytes(target);
                return bytes.length == 0 || bytes.length > MAX_ARTIFACT_BYTES ? null : bytes;
            } catch (IOException unreadable) {
                return null;
            }
        }
        return null;
    }

    private static String canonicalArtifact(String artifact) {
        if (SLOT_A.equals(artifact) || SLOT_B.equals(artifact) || HEAD.equals(artifact) || BOOTSTRAP.equals(artifact)) {
            return artifact;
        }
        throw new IllegalArgumentException("unknown journal artifact");
    }
}
