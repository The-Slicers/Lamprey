package plugins.quorum.Libraries.Mine;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public class SecPlugin {
    public java.lang.Object me_ = null;

    public String GenerateSHA(String input){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash){
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (Exception e){
            return "";
        }
    }
}
