/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.download.forge;

import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the native `binarypatcher` implementation against synthetic patch bundles.
@NotNullByDefault
public final class BinaryPatcherTest {

    /// Tool artifact that selects the `TreeMap` ordering used by 1.2.0 and later.
    private static final Artifact SORTED_TOOL = Artifact.fromDescriptor("net.minecraftforge:binarypatcher:1.2.0");

    /// Verifies that patched classes replace their clean counterparts and that classes
    /// missing from the clean jar are created.
    @Test
    public void patchesExistingAndCreatesMissingClasses(@TempDir Path tempDir) throws IOException {
        byte[] cleanClass = {1, 2, 3, 4};
        byte[] patchedClass = {9, 9, 9};
        byte[] createdClass = {7, 7};

        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of(
                "a/A.class", cleanClass,
                "a/Untouched.class", new byte[]{5}));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("a/A", true, adler32(cleanClass), literalDelta(patchedClass)),
                record("a/B", false, 0, literalDelta(createdClass))));
        Path output = tempDir.resolve("patched.jar");

        assertTrue(BinaryPatcher.apply(SORTED_TOOL, args(clean, output, bundle), Map.of()));

        Map<String, byte[]> entries = readJar(output);
        // Without --unpatched only patched and created classes survive; the TreeMap ordering
        // of binarypatcher 1.2.0 sorts the newly created class behind the patched one.
        assertEquals(List.of("a/A.class", "a/B.class"), List.copyOf(entries.keySet()));
        assertArrayEquals(patchedClass, entries.get("a/A.class"));
        assertArrayEquals(createdClass, entries.get("a/B.class"));
    }

    /// Verifies the fixed entry timestamp that keeps the output reproducible.
    @Test
    public void stampsEntriesWithTheToolTimestamp(@TempDir Path tempDir) throws IOException {
        byte[] cleanClass = {1};
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of("a/A.class", cleanClass));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("a/A", true, adler32(cleanClass), literalDelta(new byte[]{2}))));
        Path output = tempDir.resolve("patched.jar");

        assertTrue(BinaryPatcher.apply(SORTED_TOOL, args(clean, output, bundle), Map.of()));

        try (ZipFile zip = new ZipFile(output.toFile())) {
            ZipEntry entry = zip.getEntry("a/A.class");
            assertNotNull(entry);
            assertEquals("1989-11-26T00:00", entry.getTimeLocal().toString());
        }
    }

    /// Verifies that `--data` copies non-class entries, drops signature files, removes
    /// manifest digests, and that `--unpatched` keeps classes without a patch.
    @Test
    public void keepsDataAndUnpatchedEntries(@TempDir Path tempDir) throws IOException {
        byte[] cleanClass = {1};
        byte[] manifest = ("Manifest-Version: 1.0\r\n"
                + "\r\n"
                + "Name: a/A.class\r\n"
                + "SHA-256-Digest: abc\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8);

        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of(
                "a/A.class", cleanClass,
                "a/Untouched.class", new byte[]{2},
                "assets/data.txt", new byte[]{3},
                "META-INF/MANIFEST.MF", manifest,
                "META-INF/FOO.SF", new byte[]{4}));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("a/A", true, adler32(cleanClass), literalDelta(new byte[]{9}))));
        Path output = tempDir.resolve("patched.jar");

        List<String> args = new ArrayList<>(args(clean, output, bundle));
        args.add("--data");
        args.add("--unpatched");
        assertTrue(BinaryPatcher.apply(SORTED_TOOL, args, Map.of()));

        Map<String, byte[]> entries = readJar(output);
        assertEquals(Set.of("a/A.class", "a/Untouched.class", "assets/data.txt", "META-INF/MANIFEST.MF"),
                entries.keySet());
        assertArrayEquals(new byte[]{2}, entries.get("a/Untouched.class"));
        assertArrayEquals(new byte[]{3}, entries.get("assets/data.txt"));

        Manifest cleaned = new Manifest(new ByteArrayInputStream(entries.get("META-INF/MANIFEST.MF")));
        assertTrue(cleaned.getEntries().isEmpty());
    }

    /// Verifies that an empty delta removes the class from the output.
    @Test
    public void removesClassesWithEmptyDelta(@TempDir Path tempDir) throws IOException {
        byte[] cleanClass = {1, 2};
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of(
                "a/A.class", cleanClass,
                "a/B.class", new byte[]{3}));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("a/A", true, adler32(cleanClass), new byte[0])));
        Path output = tempDir.resolve("patched.jar");

        // --unpatched keeps a/B so that the output is not empty; the empty delta still
        // removes a/A.
        List<String> args = new ArrayList<>(args(clean, output, bundle));
        args.add("--unpatched");
        assertTrue(BinaryPatcher.apply(SORTED_TOOL, args, Map.of()));

        Map<String, byte[]> entries = readJar(output);
        assertEquals(Set.of("a/B.class"), entries.keySet());
    }

    /// Verifies that `--store` writes uncompressed entries and that `--marker` adds the
    /// empty marker entry used by modern Forge installers.
    @Test
    public void writesStoredEntriesAndMarker(@TempDir Path tempDir) throws IOException {
        byte[] cleanClass = {1};
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of("a/A.class", cleanClass));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("a/A", true, adler32(cleanClass), literalDelta(new byte[]{2, 3}))));
        Path output = tempDir.resolve("patched.jar");

        List<String> args = new ArrayList<>(args(clean, output, bundle));
        args.add("--store");
        args.add("--marker");
        args.add(".patched");
        assertTrue(BinaryPatcher.apply(SORTED_TOOL, args, Map.of()));

        try (ZipFile zip = new ZipFile(output.toFile())) {
            ZipEntry patched = zip.getEntry("a/A.class");
            ZipEntry marker = zip.getEntry(".patched");
            assertNotNull(patched);
            assertNotNull(marker);
            assertEquals(ZipEntry.STORED, patched.getMethod());
            assertEquals(ZipEntry.STORED, marker.getMethod());
            assertEquals(0, marker.getSize());
        }
    }

    /// Verifies that NeoForge's repackaged tool is recognized and sorts newly created
    /// classes, matching `binarypatcher` 2.1.2.
    @Test
    public void acceptsNeoForgeBinaryPatcher(@TempDir Path tempDir) throws IOException {
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of("a/Untouched.class", new byte[]{1}));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("b/Z", false, 0, literalDelta(new byte[]{2})),
                record("a/A", false, 0, literalDelta(new byte[]{3}))));
        Path output = tempDir.resolve("patched.jar");

        List<String> args = new ArrayList<>(args(clean, output, bundle));
        args.add("--unpatched");
        assertTrue(BinaryPatcher.apply(
                Artifact.fromDescriptor("net.neoforged.installertools:binarypatcher:2.1.2:fatjar"),
                args, Map.of()));

        Map<String, byte[]> entries = readJar(output);
        assertEquals(List.of("a/Untouched.class", "a/A.class", "b/Z.class"), List.copyOf(entries.keySet()));
    }

    /// Verifies that Forge 1.1.1 is accepted even though it orders created classes by
    /// hash table iteration rather than by name.
    @Test
    public void acceptsLegacyForgeBinaryPatcher(@TempDir Path tempDir) throws IOException {
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of("a/Untouched.class", new byte[]{1}));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("b/Z", false, 0, literalDelta(new byte[]{2})),
                record("a/A", false, 0, literalDelta(new byte[]{3}))));
        Path output = tempDir.resolve("patched.jar");

        List<String> args = new ArrayList<>(args(clean, output, bundle));
        args.add("--unpatched");
        assertTrue(BinaryPatcher.apply(
                Artifact.fromDescriptor("net.minecraftforge:binarypatcher:1.1.1"), args, Map.of()));

        assertEquals(Set.of("a/Untouched.class", "a/A.class", "b/Z.class"), readJar(output).keySet());
    }

    /// Verifies that unsupported invocations are rejected without touching the output.
    @Test
    public void rejectsUnsupportedInvocations(@TempDir Path tempDir) throws IOException {
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of("a/A.class", new byte[]{1}));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of());
        Path output = tempDir.resolve("patched.jar");

        // --create belongs to patch generation, not to installation.
        assertFalse(BinaryPatcher.apply(SORTED_TOOL, List.of(
                "--clean", clean.toString(),
                "--output", output.toString(),
                "--apply", bundle.toString(),
                "--create", tempDir.resolve("out.lzma").toString()), Map.of()));
        assertFalse(Files.exists(output));

        // A different tool is not handled at all.
        assertFalse(BinaryPatcher.apply(Artifact.fromDescriptor("net.minecraftforge:installertools:1.4.3"),
                args(clean, output, bundle), Map.of()));
        assertFalse(Files.exists(output));
    }

    /// Verifies that a patch for a different clean class aborts the run and removes the
    /// partial output.
    @Test
    public void removesOutputOnChecksumMismatch(@TempDir Path tempDir) throws IOException {
        byte[] cleanClass = {1, 2, 3};
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of("a/A.class", cleanClass));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("a/A", true, adler32(new byte[]{4, 5, 6}), literalDelta(new byte[]{9}))));
        Path output = tempDir.resolve("patched.jar");

        assertThrows(IOException.class, () -> BinaryPatcher.apply(SORTED_TOOL, args(clean, output, bundle), Map.of()));
        assertFalse(Files.exists(output));
    }

    /// Verifies that declared output checksums decide whether the native result is accepted.
    @Test
    public void verifiesDeclaredOutputs(@TempDir Path tempDir) throws IOException {
        byte[] cleanClass = {1};
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of("a/A.class", cleanClass));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(
                record("a/A", true, adler32(cleanClass), literalDelta(new byte[]{2}))));
        Path output = tempDir.resolve("patched.jar");

        assertTrue(BinaryPatcher.apply(SORTED_TOOL, args(clean, output, bundle), Map.of()));
        String checksum = DigestUtils.digestToString("SHA-1", output);

        assertTrue(BinaryPatcher.apply(SORTED_TOOL, args(clean, output, bundle), Map.of(output.toString(), checksum)));

        assertFalse(BinaryPatcher.apply(SORTED_TOOL, args(clean, output, bundle),
                Map.of(output.toString(), "0000000000000000000000000000000000000000")));
        assertFalse(Files.exists(output));
    }

    /// Verifies that malformed patch records are rejected.
    @Test
    public void rejectsMalformedRecords(@TempDir Path tempDir) throws IOException {
        Path clean = writeJar(tempDir.resolve("clean.jar"), Map.of("a/A.class", new byte[]{1}));
        Path bundle = writeBundle(tempDir.resolve("client.lzma"), List.of(new byte[]{2, 1, 2, 3}));
        Path output = tempDir.resolve("patched.jar");

        assertThrows(IOException.class, () -> BinaryPatcher.apply(SORTED_TOOL, args(clean, output, bundle), Map.of()));
        assertFalse(Files.exists(output));
    }

    /// Builds the processor arguments used by every test.
    private static List<String> args(Path clean, Path output, Path bundle) {
        return List.of("--clean", clean.toString(), "--output", output.toString(), "--apply", bundle.toString());
    }

    /// Computes the Adler-32 checksum stored in a patch record.
    private static int adler32(byte[] data) {
        Adler32 adler32 = new Adler32();
        adler32.update(data);
        return (int) adler32.getValue();
    }

    /// Builds one `.binpatch` record.
    ///
    /// @param obf      class name in the clean jar
    /// @param exists   whether the clean jar is expected to contain the class
    /// @param checksum Adler-32 checksum of the clean class bytes
    /// @param delta    GDIFF delta producing the patched class
    private static byte[] record(String obf, boolean exists, int checksum, byte[] delta) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(1);
        output.writeUTF(obf);
        output.writeUTF(obf);
        output.writeBoolean(exists);
        if (exists) {
            output.writeInt(checksum);
        }
        output.writeInt(delta.length);
        output.write(delta);
        return bytes.toByteArray();
    }

    /// Builds a GDIFF delta that emits [content] as a single literal run.
    private static byte[] literalDelta(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.write(new byte[]{(byte) 0xD1, (byte) 0xFF, (byte) 0xD1, (byte) 0xFF});
        output.writeByte(4);
        if (content.length <= 246) {
            output.writeByte(content.length);
        } else if (content.length <= 0xFFFF) {
            output.writeByte(247);
            output.writeShort(content.length);
        } else {
            output.writeByte(248);
            output.writeInt(content.length);
        }
        output.write(content);
        output.writeByte(0);
        return bytes.toByteArray();
    }

    /// Writes a ZIP archive to [file].
    private static Path writeJar(Path file, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    /// Writes an LZMA-compressed ZIP archive holding [records].
    private static Path writeBundle(Path file, List<byte[]> records) throws IOException {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            for (int i = 0; i < records.size(); i++) {
                zip.putNextEntry(new ZipEntry(i + ".binpatch"));
                zip.write(records.get(i));
                zip.closeEntry();
            }
        }

        try (OutputStream fileOutput = Files.newOutputStream(file);
             LZMAOutputStream output = new LZMAOutputStream(fileOutput, new LZMA2Options(), zipBytes.size())) {
            output.write(zipBytes.toByteArray());
        }
        return file;
    }

    /// Reads every entry of a jar in archive order.
    private static Map<String, byte[]> readJar(Path file) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
