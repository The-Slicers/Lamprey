package plugins.quorum.Libraries.Data.Compression;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import plugins.quorum.Libraries.System.TextFile;

/**
 * Java side of Libraries.Data.Compression.ZipReader: reads single entries of
 * a ZIP archive (a .3mf is a ZIP) without unpacking it.
 */
public class ZipReader {
    public java.lang.Object me_ = null;

    public boolean HasEntry(String zipPath, String entryName) {
        File file = new File(zipPath).getAbsoluteFile();
        if (!file.isFile()) {
            return false;
        }
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry(entryName) != null;
        } catch (IOException e) {
            throw new RuntimeException("ZipReader: " + file.getPath() + " is not a readable ZIP archive: " + e.getMessage(), e);
        }
    }

    public String ReadText(String zipPath, String entryName) {
        File file = new File(zipPath).getAbsoluteFile();
        if (!file.isFile()) {
            throw new RuntimeException("ZipReader: file not found: " + file.getPath());
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new RuntimeException("ZipReader: " + file.getPath() + " has no entry named " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    bytes.write(buffer, 0, count);
                }
                return TextFile.decode(bytes.toByteArray());
            }
        } catch (IOException e) {
            throw new RuntimeException("ZipReader: could not read " + entryName + " from " + file.getPath() + ": " + e.getMessage(), e);
        }
    }
}
