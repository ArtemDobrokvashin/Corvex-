package org.apache.james.clamav;
import java.io.*;
import java.net.*;
import jakarta.mail.MessagingException;
import org.apache.mailet.Mail;
public class ClamAVScan {
    public static InetAddress[] addresses;
    public static int port;
    public java.util.Map<String,String> parameters = java.util.Map.of();
    public void init() throws MessagingException { }
    public void destroy() { }
    public String getInitParameter(String key) { return parameters.get(key); }
    protected void checkInitParameters(java.util.Set<String> allowed) throws MessagingException {
        for (String key : parameters.keySet()) if (!allowed.contains(key)) throw new MessagingException("Invalid parameter");
    }
    protected void setAddresses(InetAddress[] values) { addresses = values; }
    public void setPort(int value) { port = value; }
    protected InetAddress[] getAddresses() { return addresses; }
    public int getPort() { return port; }
    protected Socket getClamdSocket() throws ConnectException { return null; }
    public boolean hasVirus(InputStream input) throws IOException { return false; }
    public void service(Mail mail) throws MessagingException { }
}
