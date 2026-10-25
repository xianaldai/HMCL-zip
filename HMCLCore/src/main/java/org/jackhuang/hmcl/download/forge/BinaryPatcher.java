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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.tukaani.xz.LZMAInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Applies Forge and NeoForge `binarypatcher` processors without launching an external JVM.
///
/// A patch bundle is an LZMA stream holding a ZIP archive with one `.binpatch` record per
/// patched class. Each record stores the class name in the clean jar, the mapped name,
/// whether the class exists in the clean jar, the Adler-32 checksum of the clean class
/// bytes, and a GDIFF delta. The output jar is rebuilt from the clean jar by applying every
/// delta and writing the result with fixed entry timestamps.
///
/// The output reproduces `binarypatcher` 1.0.x through 3.0.x byte for byte, including the
/// order of classes that do not exist in the clean jar: versions before 1.2.0 collect
/// patches in a `HashMap`, later versions in a `TreeMap`.
///
/// Only the options used by Forge and NeoForge install profiles are supported. Any other
/// invocation makes [#apply] return `false` so the caller can run the official tool.
@NotNullByDefault
public final class BinaryPatcher {

    /// Buffer size for reading archives and class data.
    private static final int BUFFER_SIZE = 65536;

    /// Suffix of compiled Java classes.
    private static final String CLASS_SUFFIX = ".class";

    /// Manifest path compared case-insensitively against entry names.
    private static final String MANIFEST_NAME = "meta-inf/manifest.mf";

    /// Modification time written to every output entry.
    ///
    /// `binarypatcher` sets the default time zone to GMT and stamps entries with
    /// `628041600000L`, which is 1989-11-26T00:00:00Z. [#ZipEntry#setTimeLocal] stores the
    /// same DOS timestamp without touching the JVM default time zone.
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1989, 11, 26, 0, 0);

    /// Shared empty payload for deleted classes and marker entries.
    private static final byte @Unmodifiable [] EMPTY_DATA = new byte[0];

    /// Prevents instantiation.
    private BinaryPatcher() {
    }

    /// Applies the binary patches described by a processor invocation.
    ///
    /// The output is written to the path given by `--output`, replacing any existing file.
    /// When this method throws, a partially written output file is removed so that the
    /// external processor starts from a clean state.
    ///
    /// @param tool    executable jar declared by the processor
    /// @param args    processor arguments with all placeholders already substituted
    /// @param outputs declared output paths mapped to their expected SHA-1 checksums,
    ///                empty when the install profile declares none
    /// @return `true` if the invocation was handled, `false` if it is not a supported
    ///         `binarypatcher` invocation or the result does not match [outputs], in which
    ///         case the caller must run the official tool
    /// @throws IOException if the patches cannot be applied
    public static boolean apply(Artifact tool, List<String> args, Map<String, String> outputs) throws IOException {
        if (!"binarypatcher".equals(tool.getName())) {
            return false;
        }

        Options options = Options.parse(args);
        if (options == null) {
            return false;
        }

        LOG.info("Applying binary patches natively: " + options.patches + " -> " + options.output);
        try {
            patch(options, writesSortedEntries(tool));
        } catch (IOException | RuntimeException e) {
            // Leave no partial output behind for the external processor to trip over.
            try {
                Files.deleteIfExists(options.output);
            } catch (IOException ignored) {
            }
            throw e;
        }

        if (!matchesOutputs(outputs)) {
            LOG.info("Native binary patches do not match the declared checksums, falling back to the external processor");
            for (String path : outputs.keySet()) {
                Files.deleteIfExists(Paths.get(path));
            }
            return false;
        }
        return true;
    }

    /// Returns whether classes absent from the clean jar are written in sorted order.
    ///
    /// `binarypatcher` 1.0.x and 1.1.x collect patches in a `HashMap`, so newly created
    /// entries follow the hash table order. 1.2.0 replaced it with a `TreeMap`, which sorts
    /// class names. Reproducing the exact order keeps the output byte for byte identical,
    /// which matters for the SHA-1 checksums declared in install profiles.
    ///
    /// @param tool executable jar declared by the processor
    private static boolean writesSortedEntries(Artifact tool) {
        if (!"net.minecraftforge".equals(tool.getGroup())) {
            return true;
        }

        String[] parts = tool.getVersion().split("\\.");
        if (parts.length < 2) {
            return true;
        }
        try {
            return Integer.parseInt(parts[0]) != 1 || Integer.parseInt(parts[1]) >= 2;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /// Returns whether every declared output exists with its expected SHA-1 checksum.
    ///
    /// @param outputs declared output paths mapped to their expected SHA-1 checksums
    private static boolean matchesOutputs(Map<String, String> outputs) throws IOException {
        for (Map.Entry<String, String> entry : outputs.entrySet()) {
            Path artifact = Paths.get(entry.getKey());
            if (!Files.isRegularFile(artifact)) {
                return false;
            }

            String checksum;
            try (InputStream input = Files.newInputStream(artifact)) {
                checksum = DigestUtils.digestToString("SHA-1", input);
            }
            if (!entry.getValue().equals(checksum)) {
                return false;
            }
        }
        return true;
    }

    /// Rebuilds the output jar by applying every patch record to the clean jar.
    ///
    /// @param options parsed command line
    /// @param sorted  whether classes absent from the clean jar are written in sorted order
    private static void patch(Options options, boolean sorted) throws IOException {
        Map<String, List<Patch>> patches = readPatches(options.patches, sorted);

        Path parent = options.output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (ZipInputStream clean = new ZipInputStream(new BufferedInputStream(Files.newInputStream(options.clean), BUFFER_SIZE));
             // The deflater behind ZipOutputStream emits 512-byte chunks, so an
             // unbuffered file sink costs tens of thousands of small writes.
             ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(options.output), BUFFER_SIZE))) {
            Set<String> processed = new HashSet<>();
            ZipEntry entry;
            while ((entry = clean.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(CLASS_SUFFIX)) {
                    String className = name.substring(0, name.length() - CLASS_SUFFIX.length());
                    List<Patch> classPatches = patches.get(className);
                    if (classPatches != null) {
                        processed.add(className);
                        byte[] data = readAll(clean);
                        for (Patch classPatch : classPatches) {
                            data = classPatch.apply(data);
                        }
                        // An empty result removes the class from the output.
                        if (data.length != 0) {
                            write(output, name, data, options.store);
                        }
                    } else if (options.includeUnpatched) {
                        write(output, name, readAll(clean), options.store);
                    }
                } else if (options.keepData) {
                    if (isSignatureFile(name)) {
                        continue;
                    }
                    byte[] data = readAll(clean);
                    if (MANIFEST_NAME.equals(name.toLowerCase(Locale.ROOT))) {
                        data = cleanManifest(data);
                    }
                    write(output, name, data, options.store);
                }
            }

            // Classes missing from the clean jar are created from scratch. The iteration
            // order of the patch map decides where these entries land in the output.
            for (Map.Entry<String, List<Patch>> classPatches : patches.entrySet()) {
                if (processed.contains(classPatches.getKey())) {
                    continue;
                }
                byte[] data = EMPTY_DATA;
                for (Patch classPatch : classPatches.getValue()) {
                    data = classPatch.apply(data);
                }
                if (data.length != 0) {
                    write(output, classPatches.getKey() + CLASS_SUFFIX, data, options.store);
                }
            }

            if (options.marker != null) {
                write(output, options.marker, EMPTY_DATA, true);
            }
        }
    }

    /// Reads every patch record from a `.lzma` bundle.
    ///
    /// The bundle is an LZMA stream holding a ZIP archive. Records for the same class are
    /// kept in archive order because they are applied in sequence.
    ///
    /// @param file   patch bundle
    /// @param sorted whether the returned map iterates class names in sorted order
    private static Map<String, List<Patch>> readPatches(Path file, boolean sorted) throws IOException {
        Map<String, List<Patch>> patches = sorted ? new TreeMap<>() : new HashMap<>();
        try (InputStream fileInput = Files.newInputStream(file);
             // LZMAInputStream reads the compressed stream byte by byte, so it needs a buffer
             // to avoid millions of small reads.
             InputStream input = new LZMAInputStream(new BufferedInputStream(fileInput, BUFFER_SIZE));
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().endsWith(".binpatch")) {
                    continue;
                }
                Patch patch = Patch.read(zip);
                patches.computeIfAbsent(patch.obf(), key -> new ArrayList<>()).add(patch);
            }
        }
        return patches;
    }

    /// Writes one entry to the output jar.
    ///
    /// @param output output jar
    /// @param name   entry name
    /// @param data   entry payload
    /// @param store  whether the entry is written uncompressed with an explicit CRC-32
    private static void write(ZipOutputStream output, String name, byte[] data, boolean store) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ZIP_TIME);
        if (store) {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            CRC32 crc32 = new CRC32();
            crc32.update(data);
            entry.setCrc(crc32.getValue());
        }
        output.putNextEntry(entry);
        output.write(data);
        output.closeEntry();
    }

    /// Reads the current ZIP entry to the end.
    ///
    /// @param input stream positioned at the beginning of an entry
    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    /// Returns whether [name] is a jar signature file.
    ///
    /// Signature files are dropped when data entries are kept because the patched classes
    /// invalidate them.
    ///
    /// @param name entry name
    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        return name.endsWith(".RSA") || name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".EC");
    }

    /// Removes per-entry digest attributes from a manifest.
    ///
    /// The patched classes no longer match the digests, so the attributes are removed and
    /// sections that become empty are dropped. The manifest is only re-serialized when
    /// something was removed, which keeps the bytes unchanged otherwise.
    ///
    /// @param raw original manifest bytes
    private static byte[] cleanManifest(byte[] raw) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(raw));
        boolean modified = false;
        for (Iterator<Map.Entry<String, Attributes>> sections = manifest.getEntries().entrySet().iterator(); sections.hasNext(); ) {
            Attributes attributes = sections.next().getValue();
            for (Iterator<Object> keys = attributes.keySet().iterator(); keys.hasNext(); ) {
                if (keys.next().toString().toLowerCase(Locale.ROOT).endsWith("-digest")) {
                    keys.remove();
                    modified = true;
                }
            }
            if (attributes.isEmpty()) {
                sections.remove();
            }
        }

        if (!modified) {
            return raw;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        manifest.write(output);
        return output.toByteArray();
    }

    /// Command line of a supported `binarypatcher` invocation.
    private static final class Options {

        /// Jar the patches are applied to.
        private final Path clean;

        /// Jar written by the processor.
        private final Path output;

        /// LZMA-compressed archive with one `.binpatch` record per class.
        private final Path patches;

        /// Whether entries that are not classes are copied to the output.
        private final boolean keepData;

        /// Whether classes without a patch are copied to the output.
        private final boolean includeUnpatched;

        /// Whether every entry is written uncompressed.
        private final boolean store;

        /// Name of an extra empty entry to add, or `null`.
        private final @Nullable String marker;

        /// Creates a parsed command line.
        ///
        /// @param clean            jar the patches are applied to
        /// @param output           jar written by the processor
        /// @param patches          LZMA-compressed patch bundle
        /// @param keepData         whether entries that are not classes are copied
        /// @param includeUnpatched whether classes without a patch are copied
        /// @param store            whether every entry is written uncompressed
        /// @param marker           name of an extra empty entry, or `null`
        private Options(Path clean, Path output, Path patches, boolean keepData,
                        boolean includeUnpatched, boolean store, @Nullable String marker) {
            this.clean = clean;
            this.output = output;
            this.patches = patches;
            this.keepData = keepData;
            this.includeUnpatched = includeUnpatched;
            this.store = store;
            this.marker = marker;
        }

        /// Parses the processor arguments strictly.
        ///
        /// Both `--name value` and `--name=value` forms are accepted, as the official tool
        /// does. Flags cannot take a value, and repeated options are rejected.
        ///
        /// @param args processor arguments
        /// @return the parsed options, or `null` when the invocation contains an option
        ///         this implementation does not reproduce
        private static @Nullable Options parse(List<String> args) {
            Map<String, String> values = new HashMap<>();
            boolean keepData = false;
            boolean includeUnpatched = false;
            boolean store = false;

            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (!arg.startsWith("--")) {
                    return null;
                }

                int separator = arg.indexOf('=');
                String name = separator < 0 ? arg.substring(2) : arg.substring(2, separator);
                String value = separator < 0 ? null : arg.substring(separator + 1);

                switch (name) {
                    case "data" -> {
                        if (value != null) {
                            return null;
                        }
                        keepData = true;
                    }
                    case "unpatched" -> {
                        if (value != null) {
                            return null;
                        }
                        includeUnpatched = true;
                    }
                    case "store" -> {
                        if (value != null) {
                            return null;
                        }
                        store = true;
                    }
                    case "clean", "output", "apply", "marker" -> {
                        if (value == null) {
                            if (i + 1 >= args.size()) {
                                return null;
                            }
                            value = args.get(++i);
                        }
                        if (value.isEmpty() || values.putIfAbsent(name, value) != null) {
                            return null;
                        }
                    }
                    default -> {
                        return null;
                    }
                }
            }

            String clean = values.get("clean");
            String output = values.get("output");
            String patches = values.get("apply");
            if (clean == null || output == null || patches == null) {
                return null;
            }
            return new Options(Paths.get(clean), Paths.get(output), Paths.get(patches),
                    keepData, includeUnpatched, store, values.get("marker"));
        }
    }

    /// A single class transformation stored in a `.binpatch` entry.
    ///
    /// @param obf      name of the class in the clean jar, without the `.class` suffix
    /// @param srg      name of the class after patching, kept for diagnostics only
    /// @param exists   whether the clean jar is expected to contain the class
    /// @param checksum Adler-32 checksum of the clean class bytes, zero when [exists] is `false`
    /// @param data     GDIFF delta, empty when the class is removed
    private record Patch(String obf, String srg, boolean exists, int checksum, byte @Unmodifiable [] data) {

        /// Reads one patch record from the current ZIP entry.
        ///
        /// @param stream stream positioned at the beginning of a `.binpatch` entry
        private static Patch read(InputStream stream) throws IOException {
            DataInputStream input = new DataInputStream(stream);
            int version = input.readUnsignedByte();
            if (version != 1) {
                throw new IOException("Unsupported binary patch version: " + version);
            }
            String obf = input.readUTF();
            String srg = input.readUTF();
            boolean exists = input.readBoolean();
            int checksum = exists ? input.readInt() : 0;
            int length = input.readInt();
            if (length < 0 || length > BUFFER_SIZE * 1024) {
                throw new IOException("Invalid binary patch size: " + length);
            }
            byte[] data = new byte[length];
            input.readFully(data);
            return new Patch(obf, srg, exists, checksum, data);
        }

        /// Applies this patch to the clean class bytes.
        ///
        /// @param clean class bytes currently in the jar, empty when the class does not exist
        /// @return patched class bytes, empty when the class must be removed
        private byte[] apply(byte[] clean) throws IOException {
            if (exists != (clean.length != 0)) {
                throw new IOException("Expected " + obf + (exists ? " to exist" : " to be absent") + " in the clean jar");
            }

            if (exists) {
                Adler32 adler32 = new Adler32();
                adler32.update(clean);
                if ((int) adler32.getValue() != checksum) {
                    throw new IOException("Checksum mismatch for " + obf);
                }
            }

            if (data.length == 0) {
                return EMPTY_DATA;
            }
            return GDiff.patch(clean, data);
        }
    }

    /// Decoder for the GDIFF streams produced by `javaxdelta`.
    private static final class GDiff {

        /// Magic number every GDIFF stream starts with.
        private static final byte @Unmodifiable [] MAGIC = {(byte) 0xD1, (byte) 0xFF, (byte) 0xD1, (byte) 0xFF};

        /// GDIFF format version supported by `binarypatcher`.
        private static final int VERSION = 4;

        /// Prevents instantiation.
        private GDiff() {
        }

        /// Applies a GDIFF delta to the source bytes.
        ///
        /// The stream is a header followed by commands: 0 ends the stream, 1-246 emit that
        /// many literal bytes, 247 and 248 emit a literal run with a 16-bit or 32-bit length,
        /// and 249-255 copy a run from the source with differently sized offset and length
        /// fields. All integers are big-endian.
        ///
        /// @param source bytes the delta was generated from
        /// @param patch  GDIFF delta
        /// @return reconstructed bytes
        private static byte[] patch(byte[] source, byte[] patch) throws IOException {
            if (patch.length < MAGIC.length + 1
                    || patch[0] != MAGIC[0] || patch[1] != MAGIC[1]
                    || patch[2] != MAGIC[2] || patch[3] != MAGIC[3]) {
                throw new IOException("Invalid GDIFF header");
            }
            if (patch[4] != VERSION) {
                throw new IOException("Unsupported GDIFF version: " + patch[4]);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(source.length + patch.length);
            try {
                int position = MAGIC.length + 1;
                while (true) {
                    int command = patch[position++] & 0xFF;
                    if (command == 0) {
                        return output.toByteArray();
                    }
                    if (command <= 246) {
                        output.write(patch, position, command);
                        position += command;
                        continue;
                    }

                    switch (command) {
                        case 247 -> {
                            int length = readUShort(patch, position);
                            position += 2;
                            output.write(patch, position, length);
                            position += length;
                        }
                        case 248 -> {
                            int length = readInt(patch, position);
                            position += 4;
                            output.write(patch, position, length);
                            position += length;
                        }
                        case 249 -> {
                            int offset = readUShort(patch, position);
                            position += 2;
                            int length = patch[position++] & 0xFF;
                            output.write(source, offset, length);
                        }
                        case 250 -> {
                            int offset = readUShort(patch, position);
                            position += 2;
                            int length = readUShort(patch, position);
                            position += 2;
                            output.write(source, offset, length);
                        }
                        case 251 -> {
                            int offset = readUShort(patch, position);
                            position += 2;
                            int length = readInt(patch, position);
                            position += 4;
                            output.write(source, offset, length);
                        }
                        case 252 -> {
                            int offset = readInt(patch, position);
                            position += 4;
                            int length = patch[position++] & 0xFF;
                            output.write(source, offset, length);
                        }
                        case 253 -> {
                            int offset = readInt(patch, position);
                            position += 4;
                            int length = readUShort(patch, position);
                            position += 2;
                            output.write(source, offset, length);
                        }
                        case 254 -> {
                            int offset = readInt(patch, position);
                            position += 4;
                            int length = readInt(patch, position);
                            position += 4;
                            output.write(source, offset, length);
                        }
                        case 255 -> {
                            long offset = readLong(patch, position);
                            position += 8;
                            int length = readInt(patch, position);
                            position += 4;
                            output.write(source, (int) offset, length);
                        }
                        default -> throw new IOException("Invalid GDIFF command: " + command);
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                throw new IOException("Truncated GDIFF stream", e);
            }
        }

        /// Reads an unsigned 16-bit big-endian integer.
        private static int readUShort(byte[] data, int position) {
            return ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        }

        /// Reads a 32-bit big-endian integer.
        private static int readInt(byte[] data, int position) {
            return ((data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
        }

        /// Reads a 64-bit big-endian integer.
        private static long readLong(byte[] data, int position) {
            return ((long) readInt(data, position) << 32) | (readInt(data, position + 4) & 0xFFFFFFFFL);
        }
    }
}
