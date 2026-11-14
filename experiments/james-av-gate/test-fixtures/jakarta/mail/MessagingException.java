package jakarta.mail;
public class MessagingException extends Exception {
    private static final long serialVersionUID = 1L;
    public MessagingException() { }
    public MessagingException(String message) { super(message); }
    public MessagingException(String message, Exception cause) { super(message, cause); }
}
