package dev.localsearch.storage;

import dev.localsearch.document.Document;
import dev.localsearch.index.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Explicit v1 binary snapshots; CURRENT is the sole atomic commit point. */
public final class IndexStorage {
    private static final int MAGIC = 0x4C465453, VERSION = 1;
    public static class MissingIndex extends IOException {
        public MissingIndex() { super("Index not found. Run 'index PATH' first."); }
    }
    public boolean exists(Path directory) { return Files.isRegularFile(directory.resolve("CURRENT")); }
    public InvertedIndex load(Path directory) throws IOException {
        if (!exists(directory)) throw new MissingIndex();
        try {
            String name = Files.readString(directory.resolve("CURRENT")).strip();
            if (!name.matches("snapshot-[a-f0-9-]+\\.bin")) throw new IOException("Invalid snapshot pointer");
            Path file = directory.resolve(name);
            CRC32 crc = new CRC32();
            try (var checked = new CheckedInputStream(new BufferedInputStream(Files.newInputStream(file)), crc);
                 var in = new DataInputStream(checked)) {
                if (in.readInt() != MAGIC) throw new IOException("Invalid index signature");
                int version = in.readInt();
                if (version != VERSION) throw new IOException("Unsupported index version " + version + "; rebuild with this application");
                InvertedIndex index = new InvertedIndex(directory);
                index.root = readString(in); index.created = in.readLong(); index.updated = in.readLong(); index.nextId = in.readInt();
                index.maxFileSize = in.readLong();
                index.stopWords = Set.copyOf(readStrings(in)); index.excludes = readStrings(in);
                int docs = count(in);
                for (int i = 0; i < docs; i++) {
                    Document doc = new Document(in.readInt(), readString(in), readString(in), readString(in), in.readLong(), in.readLong(), readString(in), count(in));
                    String content = readString(in);
                    if (!content.matches("[a-f0-9-]+\\.txt") || index.documents().containsKey(doc.id()) || doc.id() <= 0 || doc.id() >= index.nextId)
                        throw new IOException("Invalid document record");
                    index.register(doc, content);
                }
                int terms = count(in);
                for (int t = 0; t < terms; t++) {
                    String term = readString(in);
                    int df = count(in);
                    if (df > docs || index.vocabulary().contains(term)) throw new IOException("Invalid posting list");
                    for (int p = 0; p < df; p++) {
                        int id = in.readInt(), frequency = count(in);
                        if (!index.documents().containsKey(id) || frequency == 0 || index.postings(term).containsKey(id)) throw new IOException("Invalid posting");
                        int[] positions = new int[frequency];
                        for (int j = 0; j < frequency; j++) {
                            positions[j] = in.readInt();
                            if (positions[j] < 0 || positions[j] >= index.documents().get(id).tokenCount() || (j > 0 && positions[j] <= positions[j - 1])) throw new IOException("Invalid positions");
                        }
                        index.putPosting(term, new Posting(id, positions));
                    }
                }
                long actual = crc.getValue(), expected = in.readLong();
                if (actual != expected || in.read() != -1) throw new IOException("Index checksum mismatch");
                return index;
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("Cannot read index: " + (e instanceof EOFException ? "truncated snapshot" : e.getMessage()) + ". Restore a backup or rebuild in a new index directory.", e);
        }
    }
    public void save(InvertedIndex index) throws IOException {
        Files.createDirectories(index.directory);
        String name = "snapshot-" + UUID.randomUUID() + ".bin";
        Path target = index.directory.resolve(name);
        CRC32 crc = new CRC32();
        try (var checked = new CheckedOutputStream(new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)), crc);
             var out = new DataOutputStream(checked)) {
            out.writeInt(MAGIC); out.writeInt(VERSION);
            writeString(out, index.root); out.writeLong(index.created); out.writeLong(index.updated); out.writeInt(index.nextId);
            out.writeLong(index.maxFileSize); writeStrings(out, new TreeSet<>(index.stopWords)); writeStrings(out, index.excludes);
            out.writeInt(index.documents().size());
            for (Document doc : index.documents().values()) {
                out.writeInt(doc.id()); writeString(out, doc.path()); writeString(out, doc.fileName()); writeString(out, doc.extension());
                out.writeLong(doc.size()); out.writeLong(doc.modifiedTime()); writeString(out, doc.contentHash()); out.writeInt(doc.tokenCount());
                writeString(out, index.contentFile(doc.id()));
            }
            out.writeInt(index.vocabulary().size());
            for (String term : index.vocabulary()) {
                writeString(out, term); var postings = index.postings(term); out.writeInt(postings.size());
                for (Posting posting : postings.values()) {
                    out.writeInt(posting.documentId()); out.writeInt(posting.frequency());
                    for (int position : posting.positions()) out.writeInt(position);
                }
            }
            // Flush before reading the checksum: CheckedOutputStream sees only flushed bytes.
            out.flush(); out.writeLong(crc.getValue());
        }
        force(target);
        Path pointer = index.directory.resolve("CURRENT-" + UUID.randomUUID());
        Files.writeString(pointer, name, StandardOpenOption.CREATE_NEW); force(pointer);
        Files.move(pointer, index.directory.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 20_000_000) throw new IOException("Invalid index length");
        return n;
    }
    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readString(DataInputStream in) throws IOException {
        int size = count(in);
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void writeStrings(DataOutputStream out, Collection<String> strings) throws IOException {
        out.writeInt(strings.size()); for (String s : strings) writeString(out, s);
    }
    private static List<String> readStrings(DataInputStream in) throws IOException {
        int size = count(in); List<String> strings = new ArrayList<>();
        for (int i = 0; i < size; i++) strings.add(readString(in));
        return List.copyOf(strings);
    }
}
