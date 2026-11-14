package jakarta.mail.internet;
import java.io.IOException;
import java.io.OutputStream;
import jakarta.mail.MessagingException;
public class MimeMessage {
    public void writeTo(OutputStream out) throws IOException, MessagingException { out.write(new byte[]{1, 2}); }
}
