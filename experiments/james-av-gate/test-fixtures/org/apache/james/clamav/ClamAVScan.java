package org.apache.james.clamav;
import java.io.*;
import java.net.*;
import jakarta.mail.MessagingException;
import org.apache.mailet.Mail;
public class ClamAVScan {
    public static InetAddress[] addresses;
    public static int port;
    protected InetAddress[] getAddresses() { return addresses; }
    public int getPort() { return port; }
    protected Socket getClamdSocket() throws ConnectException { return null; }
    public boolean hasVirus(InputStream input) throws IOException { return false; }
    public void service(Mail mail) throws MessagingException { }
}
