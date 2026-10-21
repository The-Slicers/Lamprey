package plugins.quorum.Libraries.Mine;

import java.io.*;
import java.util.zip.*;

public class ZipPlugin {
    public java.lang.Object me_ = null;

    public void zip_file(String inFolder, String filePath) {
        try {
            File src = new File(inFolder);
            FileOutputStream fileOutStream = new FileOutputStream(filePath);
            ZipOutputStream zipOutStream = new ZipOutputStream(fileOutStream);

            for (File file : src.listFiles()) {
                if (!file.isDirectory()) {
                    FileInputStream fileInStream = new FileInputStream(file);
                    ZipEntry zipFile = new ZipEntry(file.getName());
                    zipOutStream.putNextEntry(zipEntry);

                    byte[] b = new byte[1024];
                    int length;
                    while ((length = fileInStream.read(b)) >= 0) {
                        zipOutStream.write(b, 0, length);
                    }
                    fileInStream.close();
                }
            }
            zipOutStream.close();
            fileOutStream.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }    }
}