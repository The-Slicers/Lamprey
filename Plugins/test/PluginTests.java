import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

import plugins.quorum.Libraries.Data.Compression.ZipWriter;

/**
 * Tests for Lamprey's Java plugin, ZipWriter (Plugins/src), runnable without
 * Quorum or any test library: sh Plugins/test.sh. The archives are read back
 * with java.util.zip.
 */
public class PluginTests {
    private static int checks = 0;
    private static final List<String> failures = new ArrayList<>();
    private static File scratch;

    public static void main(String[] args) throws Exception {
        scratch = Files.createTempDirectory("lamprey-plugin-tests").toFile();
        zipWriter();
        md5();
        files();
        thumbnails();
        misuse();
        System.out.println(checks + " checks, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAIL " + failure);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    static void zipWriter() throws Exception {
        File archive = new File(scratch, "new/folder/test.zip");
        ZipWriter zip = new ZipWriter();
        zip.Open(archive.getPath());
        zip.AddText("a.txt", "plain");
        zip.AddText("Metadata/b.txt", "\u00fcnicode \u2713");
        zip.BeginImage(2, 2);
        zip.AddImage("p.png");
        zip.AddCopy("q.png", "p.png");
        zip.Close();
        zip.Close();
        check("Open creates missing folders", archive.isFile());

        try (ZipFile reader = new ZipFile(archive)) {
            List<String> names = new ArrayList<>();
            reader.stream().forEach(entry -> names.add(entry.getName()));
            check("entries are written in order", names.toString().equals("[a.txt, Metadata/b.txt, p.png, q.png]"));
            check("a copy has the same bytes", read(reader, "p.png").length == read(reader, "q.png").length);
        }

        try (ZipFile reader = new ZipFile(archive)) {
            check("text round-trips as UTF-8", new String(read(reader, "Metadata/b.txt"), StandardCharsets.UTF_8).equals("\u00fcnicode \u2713"));
        }
    }

    static void md5() throws Exception {
        File archive = new File(scratch, "md5.zip");
        ZipWriter zip = new ZipWriter();
        zip.Open(archive.getPath());
        zip.AddMd5("empty.md5", "");
        zip.AddMd5("hello.md5", "hello");
        zip.AddMd5("utf8.md5", "\u00e9");
        zip.Close();
        try (ZipFile reader = new ZipFile(archive)) {
            check("MD5 of empty text", text(reader, "empty.md5").equals("D41D8CD98F00B204E9800998ECF8427E"));
            check("MD5 of hello", text(reader, "hello.md5").equals("5D41402ABC4B2A76B9719D911017C592"));
            check("MD5 hashes the UTF-8 bytes", text(reader, "utf8.md5").equals("66DDCD97CFDEABB2F6FB8A999B4BC76F"));
        }
        check("AddMd5 before Open stops", errorOf(() -> new ZipWriter().AddMd5("a.md5", "x")) != null);
    }

    static void files() throws Exception {
        // a file larger than the 64 KB read chunk, with a splice past the first chunk
        StringBuilder body = new StringBuilder();
        body.append("; HEADER_BLOCK_START\n; old header\n; HEADER_BLOCK_END\n");
        for (int i = 0; i < 20000; i++) {
            body.append("G1 X").append(i % 250).append(".125 Y").append(i % 97).append(".5 E0.04321\n");
        }
        String content = body.toString();
        File source = new File(scratch, "plate.gcode");
        Files.write(source.toPath(), content.getBytes(StandardCharsets.UTF_8));
        int from = content.indexOf("; old header");
        int to = content.indexOf("; HEADER_BLOCK_END");
        String replacement = "; new header \u00e9\n";
        String spliced = content.substring(0, from) + replacement + content.substring(to);
        int farFrom = 70000;
        int farTo = 70100;
        String farSpliced = content.substring(0, farFrom) + "X" + content.substring(farTo);

        File archive = new File(scratch, "files.zip");
        ZipWriter zip = new ZipWriter();
        zip.Open(archive.getPath());
        zip.AddFile("plain.gcode", source.getPath(), 0, 0, "");
        zip.AddMd5OfFile("spliced.gcode.md5", source.getPath(), from, to, replacement);
        zip.AddFile("spliced.gcode", source.getPath(), from, to, replacement);
        zip.AddMd5("text.md5", spliced);
        zip.AddFile("far.gcode", source.getPath(), farFrom, farTo, "X");
        zip.AddFile("all.gcode", source.getPath(), 0, content.length(), "");
        // positions count characters of the text, as GetSubtext does, and the
        // result is encoded like AddText: check with non-ASCII text and a
        // surrogate pair straddling the 64 K character chunk boundary
        StringBuilder wide = new StringBuilder();
        wide.append("\u00b0C header \u00e9\n");
        while (wide.length() < 65535) {
            wide.append('x');
        }
        wide.append("\ud83d\ude00 tail \u2713\n");
        String wideText = wide.toString();
        File wideFile = new File(scratch, "wide.gcode");
        Files.write(wideFile.toPath(), wideText.getBytes(java.nio.charset.Charset.defaultCharset()));
        String wideSpliced = wideText.substring(0, 3) + "\u00e9!" + wideText.substring(9);
        zip.AddFile("wide.gcode", wideFile.getPath(), 3, 9, "\u00e9!");
        zip.AddMd5OfFile("wide.gcode.md5", wideFile.getPath(), 3, 9, "\u00e9!");
        zip.AddMd5("wide.text.md5", wideSpliced);
        zip.AddFile("end.gcode", wideFile.getPath(), wideText.length(), wideText.length(), "END");
        String start = zip.ReadStart(wideFile.getPath(), 5);
        String whole = zip.ReadStart(source.getPath(), content.length() + 1000);
        String badRange = errorOf(() -> zip.AddFile("bad.gcode", source.getPath(), 10, 5, ""));
        String pastEnd = errorOf(() -> zip.AddFile("bad2.gcode", source.getPath(), 0, content.length() + 1, ""));
        String missing = errorOf(() -> zip.AddFile("bad3.gcode", new File(scratch, "nope.gcode").getPath(), 0, 0, ""));
        String duplicate = errorOf(() -> zip.AddFile("plain.gcode", source.getPath(), 0, 0, ""));
        zip.Close();
        try (ZipFile reader = new ZipFile(archive)) {
            check("AddFile copies a file unchanged", new String(read(reader, "plain.gcode"), StandardCharsets.UTF_8).equals(content));
            check("AddFile replaces the given bytes", new String(read(reader, "spliced.gcode"), StandardCharsets.UTF_8).equals(spliced));
            check("AddFile splices past the first chunk", new String(read(reader, "far.gcode"), StandardCharsets.UTF_8).equals(farSpliced));
            check("AddFile can replace everything with nothing", read(reader, "all.gcode").length == 0);
            check("AddMd5OfFile matches AddMd5 of the same text", text(reader, "spliced.gcode.md5").equals(text(reader, "text.md5")));
            check("AddMd5OfFile is 32 hex digits", text(reader, "spliced.gcode.md5").matches("[0-9A-F]{32}"));
            check("AddFile counts characters and encodes like AddText", java.util.Arrays.equals(read(reader, "wide.gcode"), wideSpliced.getBytes(StandardCharsets.UTF_8)));
            check("AddMd5OfFile matches AddMd5 for non-ASCII text", text(reader, "wide.gcode.md5").equals(text(reader, "wide.text.md5")));
            check("AddFile can append at the very end", new String(read(reader, "end.gcode"), StandardCharsets.UTF_8).equals(wideText + "END"));
        }
        check("ReadStart reads the first characters", start.equals(wideText.substring(0, 5)));
        check("ReadStart stops at the end of a short file", whole.equals(content));
        check("AddFile refuses a backwards range", badRange != null && badRange.contains("bad.gcode"));
        check("AddFile refuses a range past the end", pastEnd != null && pastEnd.contains("bad2.gcode"));
        check("AddFile names a missing file", missing != null && missing.contains("nope.gcode"));
        check("AddFile refuses a duplicate entry", duplicate != null && duplicate.contains("plain.gcode"));
    }

    static void thumbnails() throws Exception {
        File archive = new File(scratch, "image.zip");
        ZipWriter zip = new ZipWriter();
        zip.Open(archive.getPath());
        zip.BeginImage(4, 3);
        zip.SetPixel(1, 2, 255, 0, 0, 255);
        zip.SetPixel(2, 1, 300, -5, 10, 128);
        zip.SetPixel(9, 9, 1, 1, 1, 1);
        zip.SetPixel(-1, 0, 1, 1, 1, 1);
        zip.AddImage("img.png");
        zip.Close();
        try (ZipFile reader = new ZipFile(archive)) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(read(reader, "img.png")));
            check("the PNG decodes", image != null);
            check("the PNG has the requested size", image.getWidth() == 4 && image.getHeight() == 3);
            check("a set pixel keeps its colour", image.getRGB(1, 2) == 0xFFFF0000);
            check("channels are clamped to 0 - 255", image.getRGB(2, 1) == 0x80FF000A);
            check("untouched pixels are transparent", (image.getRGB(0, 0) >>> 24) == 0);
        }
    }

    static void misuse() {
        check("adding before Open stops", errorOf(() -> new ZipWriter().AddText("a", "b")) != null);
        check("AddImage before BeginImage stops", errorOf(() -> {
            ZipWriter zip = new ZipWriter();
            zip.Open(new File(scratch, "noimage.zip").getPath());
            zip.AddImage("x.png");
        }) != null);
        ZipWriter zip = new ZipWriter();
        zip.Open(new File(scratch, "dup.zip").getPath());
        zip.AddText("a.txt", "1");
        String duplicate = errorOf(() -> zip.AddText("a.txt", "2"));
        check("a duplicate entry is named in the error", duplicate != null && duplicate.contains("a.txt"));
        String copy = errorOf(() -> zip.AddCopy("b.txt", "never.txt"));
        check("copying a missing entry names it", copy != null && copy.contains("never.txt"));
        String textCopy = errorOf(() -> zip.AddCopy("c.txt", "a.txt"));
        check("only images can be copied", textCopy != null && textCopy.contains("a.txt") && textCopy.contains("image"));
        zip.Close();
        check("Open of an impossible path stops", errorOf(() -> {
            File blocker = new File(scratch, "blocker");
            blocker.mkdirs();
            new ZipWriter().Open(blocker.getPath());
        }) != null);
    }

    interface Action {
        void run() throws Exception;
    }

    static String errorOf(Action action) {
        try {
            action.run();
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    static String text(ZipFile zip, String name) throws Exception {
        return new String(read(zip, name), StandardCharsets.US_ASCII);
    }

    static byte[] read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        try (InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    static void check(String what, boolean passed) {
        checks++;
        if (!passed) {
            failures.add(what);
        }
    }
}
