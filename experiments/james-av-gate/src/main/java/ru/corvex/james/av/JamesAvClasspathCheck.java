package ru.corvex.james.av;

/** One-shot classpath check, not a running James mailet or a mail flow test. */
public final class JamesAvClasspathCheck {
    private JamesAvClasspathCheck() { }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (args.length == 1 && "parent".equals(args[0])) {
            try {
                Class.forName("org.apache.james.clamav.ClamAVScan", false, loader);
                System.out.println("CLAMAV_PARENT_PRESENT");
            } catch (ClassNotFoundException e) {
                System.out.println("CLAMAV_PARENT_ABSENT");
            }
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Unexpected argument");
        Class<?> gate = Class.forName("ru.corvex.james.av.CorvexClamAVGate", true, loader);
        Object instance = gate.getDeclaredConstructor().newInstance();
        Class<?> mailet = Class.forName("org.apache.mailet.Mailet", false, loader);
        if (!mailet.isInstance(instance)) throw new IllegalStateException("Mailet API mismatch");
        System.out.println("PASS: extension class instantiated against container runtime classpath");
        System.out.println("NOT TESTED: running James loader, mailet config, ClamAV, or message flow");
    }
}
