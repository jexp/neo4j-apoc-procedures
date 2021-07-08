package apoc.data.email;

import apoc.Extended;
import apoc.util.Util;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import javax.mail.internet.*;
import java.util.Map;

@Extended
public class ExtractEmail {

    @UserFunction("apoc.data.email")
    @Description("apoc.data.email('email_address') as {personal,user,domain} - extract the personal name, user and domain as a map")
    public Map<String,String> email(final @Name("email_address") String value) {
        if (value == null || value.indexOf('@') == -1) {
            return null;
        }
        try {
            InternetAddress addr = new InternetAddress(value);
            String rawAddr = addr.getAddress();
            int idx = rawAddr.indexOf('@');

            return (Map)Util.map("personal",addr.getPersonal(), "user", rawAddr.substring(0, idx), "domain",rawAddr.substring(idx+1));
        } catch(AddressException adr) {
            return null;
        }
    }
}
