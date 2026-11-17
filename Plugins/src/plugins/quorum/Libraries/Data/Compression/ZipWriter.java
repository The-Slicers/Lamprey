package plugins.quorum.Libraries.Data.Compression;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import javax.imageio.ImageIO;

/**
 * Java side of Libraries.Data.Compression.ZipWriter. Uses only java.util.zip,
 * javax.imageio and java.security.MessageDigest, which are part of every Java
 * runtime.
 */
public class ZipWriter {
    public java.lang.Object me_ = null;

    private ZipOutputStream zip;
    private String archivePath = "";
    private BufferedImage image;
    // The image's own pixel array (TYPE_INT_ARGB stores each pixel as one
    // ARGB int), written directly: BufferedImage.setRGB allocates an int[]
    // on every call.
    private int[] pixels;
    private int imageWidth;
    // Every entry name added so far, to refuse duplicates.
    private final Set<String> names = new HashSet<>();
    // The bytes of image entries only, the ones AddCopy can copy. Text and
    // file entries are not kept, so a large G-code entry is not held in
    // memory until Close.
    private final Map<String, byte[]> images = new HashMap<>();
    private static final int CHUNK = 64 * 1024;

    public void Open(String path) {
        Close();
        File file = new File(path).getAbsoluteFile();
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
            throw new RuntimeException("ZipWriter: could not create the folder " + folder.getPath());
        }
        try {
            zip = new ZipOutputStream(new FileOutputStream(file));
            zip.setLevel(9);
            archivePath = file.getPath();
        } catch (IOException e) {
            throw new RuntimeException("ZipWriter: could not create " + file.getPath() + ": " + e.getMessage(), e);
        }
    }

    public void AddText(String entryName, String content) {
        addBytes(entryName, content.getBytes(StandardCharsets.UTF_8));
    }

    public void BeginImage(int width, int height) {
        image = new BufferedImage(Math.max(width, 1), Math.max(height, 1), BufferedImage.TYPE_INT_ARGB);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        imageWidth = image.getWidth();
    }

    public void SetPixel(int x, int y, int red, int green, int blue, int alpha) {
        if (image == null || x < 0 || y < 0 || x >= imageWidth || y >= image.getHeight()) {
            return;
        }
        int argb = clamp(alpha) << 24 | clamp(red) << 16 | clamp(green) << 8 | clamp(blue);
        pixels[y * imageWidth + x] = argb;
    }

    public void AddImage(String entryName) {
        if (image == null) {
            throw new RuntimeException("ZipWriter: AddImage(" + entryName + ") was called before BeginImage");
        }
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            byte[] bytes = png.toByteArray();
            addBytes(entryName, bytes);
            images.put(entryName, bytes);
        } catch (IOException e) {
            throw new RuntimeException("ZipWriter: could not encode " + entryName + " as PNG: " + e.getMessage(), e);
        }
    }

    public void AddCopy(String entryName, String existingEntryName) {
        byte[] bytes = images.get(existingEntryName);
        if (bytes == null) {
            if (names.contains(existingEntryName)) {
                throw new RuntimeException("ZipWriter: AddCopy(" + entryName + ") can only copy an image, and "
                    + existingEntryName + " in " + archivePath + " is not one");
            }
            throw new RuntimeException("ZipWriter: AddCopy(" + entryName + ") needs " + existingEntryName
                + ", which has not been added to " + archivePath + " yet");
        }
        addBytes(entryName, bytes);
        images.put(entryName, bytes);
    }

    public void AddMd5(String entryName, String content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8));
            addBytes(entryName, hexOf(digest).getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("ZipWriter: this Java runtime has no MD5 support", e);
        }
    }

    /**
     * Adds a text file as an entry, read a piece at a time rather than all
     * at once. The file is read as text the way Quorum's File:Read reads it
     * (java.io.FileReader, the platform's default character set) and stored
     * as UTF-8, like AddText. The characters from "from" up to "to" are
     * replaced by the replacement text, so the entry holds exactly what
     * AddText(entryName, text:GetSubtext(0, from) + replacement +
     * text:GetSubtext(to)) would, where text is the file read whole.
     * from = to = 0 with "" adds the file unchanged.
     */
    public void AddFile(String entryName, String path, int from, int to, String replacement) {
        checkOpen(entryName);
        File file = checkSplice(entryName, path, from, to);
        try {
            startEntry(entryName);
            splice(file, from, to, replacement, zip);
            zip.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException("ZipWriter: could not add " + entryName + " to " + archivePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Adds a text entry holding the MD5 checksum of exactly the bytes
     * AddFile(..., path, from, to, replacement) writes: the same 32
     * upper-case hexadecimal digits as AddMd5. The file is read in pieces.
     */
    public void AddMd5OfFile(String entryName, String path, int from, int to, String replacement) {
        checkOpen(entryName);
        File file = checkSplice(entryName, path, from, to);
        try {
            final MessageDigest md5 = MessageDigest.getInstance("MD5");
            OutputStream digest = new OutputStream() {
                @Override
                public void write(int b) {
                    md5.update((byte) b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    md5.update(b, off, len);
                }
            };
            splice(file, from, to, replacement, digest);
            addBytes(entryName, hexOf(md5.digest()).getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("ZipWriter: this Java runtime has no MD5 support", e);
        } catch (IOException e) {
            throw new RuntimeException("ZipWriter: could not read " + path + " for " + entryName + ": " + e.getMessage(), e);
        }
    }

    /**
     * The first characters of a text file (all of it if it is shorter),
     * read the way Quorum's File:Read reads the whole file, so the result is
     * the same as File:Read followed by GetSubtext(0, count). "" for an
     * empty file.
     */
    public String ReadStart(String path, int count) {
        File file = new File(path).getAbsoluteFile();
        if (!file.isFile()) {
            throw new RuntimeException("ZipWriter: ReadStart needs the file " + file.getPath() + ", which does not exist");
        }
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[8192];
        try (Reader in = new java.io.FileReader(file)) {
            while (result.length() < count) {
                int read = in.read(buffer, 0, Math.min(buffer.length, count - result.length()));
                if (read < 0) {
                    break;
                }
                result.append(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new RuntimeException("ZipWriter: could not read " + file.getPath() + ": " + e.getMessage(), e);
        }
        return result.toString();
    }

    public void Close() {
        names.clear();
        images.clear();
        if (zip == null) {
            return;
        }
        try {
            zip.close();
        } catch (IOException e) {
            throw new RuntimeException("ZipWriter: could not finish " + archivePath + ": " + e.getMessage(), e);
        } finally {
            zip = null;
        }
    }

    private void addBytes(String entryName, byte[] bytes) {
        checkOpen(entryName);
        try {
            startEntry(entryName);
            zip.write(bytes);
            zip.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException("ZipWriter: could not add " + entryName + " to " + archivePath + ": " + e.getMessage(), e);
        }
    }

    private void checkOpen(String entryName) {
        if (zip == null) {
            throw new RuntimeException("ZipWriter: " + entryName + " was added before Open was called");
        }
        if (names.contains(entryName)) {
            throw new RuntimeException("ZipWriter: " + archivePath + " already has an entry named " + entryName);
        }
    }

    private void startEntry(String entryName) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        names.add(entryName);
    }

    private File checkSplice(String entryName, String path, int from, int to) {
        File file = new File(path).getAbsoluteFile();
        if (!file.isFile()) {
            throw new RuntimeException("ZipWriter: " + entryName + " needs the file " + file.getPath() + ", which does not exist");
        }
        if (from < 0 || to < from) {
            throw new RuntimeException("ZipWriter: " + entryName + " can't replace characters " + from + " to " + to
                + " of " + file.getPath());
        }
        return file;
    }

    /**
     * Writes, as UTF-8, the file's text [0, from) + replacement + [to, end),
     * a chunk at a time. The bytes are the same as String.getBytes(UTF_8) of
     * that whole text: a surrogate pair split across two pieces is held back
     * until its second half arrives, and anything unpaired becomes '?'.
     */
    private static void splice(File file, int from, int to, String replacement, OutputStream out) throws IOException {
        Utf8Sink sink = new Utf8Sink(out);
        char[] buffer = new char[CHUNK];
        try (Reader in = new java.io.FileReader(file)) {
            long position = 0;
            int count;
            boolean replaced = false;
            while ((count = in.read(buffer)) > 0) {
                int start = 0;
                if (!replaced) {
                    long chunkEnd = position + count;
                    if (from < chunkEnd) {
                        int before = (int) (from - position);
                        sink.write(buffer, 0, before);
                        sink.write(replacement);
                        replaced = true;
                        start = (int) Math.min(count, Math.max(0, to - position));
                    } else {
                        start = count;
                        sink.write(buffer, 0, count);
                    }
                } else if (position < to) {
                    start = (int) Math.min(count, to - position);
                }
                if (start < count) {
                    sink.write(buffer, start, count - start);
                }
                position += count;
            }
            if (!replaced) {
                if (from != position) {
                    throw new IOException("can't replace characters " + from + " to " + to + ": the text has only " + position);
                }
                sink.write(replacement);
            }
            if (to > position) {
                throw new IOException("can't replace characters " + from + " to " + to + ": the text has only " + position);
            }
        }
        sink.finish();
    }

    /** UTF-8 encoding of a stream of chars, identical to String.getBytes(UTF_8) of the whole. */
    private static final class Utf8Sink {
        private final OutputStream out;
        private char pendingHigh;
        private boolean hasPending;

        Utf8Sink(OutputStream out) {
            this.out = out;
        }

        void write(String text) throws IOException {
            char[] chars = text.toCharArray();
            write(chars, 0, chars.length);
        }

        void write(char[] chars, int offset, int length) throws IOException {
            if (length <= 0) {
                return;
            }
            StringBuilder piece = new StringBuilder(length + 1);
            if (hasPending) {
                piece.append(pendingHigh);
                hasPending = false;
            }
            piece.append(chars, offset, length);
            char last = piece.charAt(piece.length() - 1);
            if (Character.isHighSurrogate(last)) {
                pendingHigh = last;
                hasPending = true;
                piece.setLength(piece.length() - 1);
            }
            if (piece.length() > 0) {
                out.write(piece.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        void finish() throws IOException {
            if (hasPending) {
                out.write(String.valueOf(pendingHigh).getBytes(StandardCharsets.UTF_8));
                hasPending = false;
            }
        }
    }

    private static String hexOf(byte[] digest) {
        StringBuilder hex = new StringBuilder(32);
        for (byte b : digest) {
            hex.append(String.format("%02X", b & 0xFF));
        }
        return hex.toString();
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }
}
