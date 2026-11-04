package plugins.quorum.Libraries.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Java side of Libraries.Data.Hash, using java.security.MessageDigest.
 */
public class Hash {
    public java.lang.Object me_ = null;

    public String Md5Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(String.format("%02X", b & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash: this Java runtime has no MD5 support", e);
        }
    }
}
