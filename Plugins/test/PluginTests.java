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
