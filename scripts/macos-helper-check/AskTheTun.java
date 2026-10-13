import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;

/**
 * The question the app asks a tun to learn that it names programs
 * (MacOsTunController.askTheTun), asked the way the app asks it: the JDK's own
 * DNS provider, by datagram, from this process.
 */
public class AskTheTun {
    public static void main(String[] args) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        environment.put(Context.PROVIDER_URL, "dns://172.19.0.2");
        environment.put("com.sun.jndi.dns.timeout.initial", "400");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        long began = System.currentTimeMillis();
        String last = "nothing";
        for (int asked = 1; asked <= 40; asked++) {
            try {
                InitialDirContext context = new InitialDirContext(environment);
                try {
                    Attribute answer = context.getAttributes(args[0], new String[] {"A"}).get("A");
                    if (answer != null) {
                        System.out.println("answered " + answer.get(0) + " after " + (System.currentTimeMillis() - began)
                            + " ms, asked " + asked + " time(s)");
                        return;
                    }
                    last = "no A record";
                } finally {
                    context.close();
                }
            } catch (Exception e) {
                last = e.getClass().getSimpleName();
            }
            Thread.sleep(300);
        }
        System.out.println("never answered in " + (System.currentTimeMillis() - began) + " ms: " + last);
    }
}
