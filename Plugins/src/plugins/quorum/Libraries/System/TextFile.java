package plugins.quorum.Libraries.System;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Java side of Libraries.System.TextFile: whole files as UTF-8 text. Invalid
 * bytes become the replacement character instead of stopping the program.
 */
public class TextFile {
    public java.lang.Object me_ = null;

    public boolean Exists(String path) {
        return new File(path).isFile();
    }

    public String GetAbsolutePath(String path) {
        return new File(path).getAbsolutePath();
    }

    public String Read(String path) {
        File file = new File(path).getAbsoluteFile();
        if (!file.isFile()) {
            throw new RuntimeException("TextFile: file not found: " + file.getPath());
        }
        try {
            return decode(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            throw new RuntimeException("TextFile: could not read " + file.getPath() + ": " + e.getMessage(), e);
        }
    }

    public static String decode(byte[] bytes) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }
}
