package apoc.data.email;

import apoc.util.TestUtil;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import static apoc.util.TestUtil.testCall;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;

public class ExtractEmailTest {

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule();

    @BeforeClass
    public static void setUp() throws Exception {
        TestUtil.registerProcedure(db, ExtractEmail.class);
    }

    @Test
    public void testPersonalPresent() {
        testCall(db, "RETURN apoc.data.email('David <david.allen@neo4j.com>').personal AS value",
            row -> assertEquals("David", row.get("value")));
    }

    @Test
    public void testPersonalMissing() {
        testCall(db, "RETURN apoc.data.email('<david.allen@neo4j.com>').personal AS value",
            row -> assertEquals(null, row.get("value")));
    }

    @Test
    public void testUser() {
        testCall(db, "RETURN apoc.data.email('Unusual <example-throwaway@gmail.com>').user AS value",
        row -> assertEquals("example-throwaway", row.get("value")));    
    }

    @Test
    public void testMissingUser() {
        testCall(db, "RETURN apoc.data.email('mail.com').user AS value",
        row -> assertEquals(null, row.get("value")));    
    }

    @Test
    public void testQuotedEmail() {
        testCall(db, "RETURN apoc.data.email('<foo@bar.baz>').domain AS value",
                row -> Assert.assertThat(row.get("value"), equalTo("bar.baz")));
    }

    @Test
    public void testEmail() {
        testCall(db, "RETURN apoc.data.email('foo@bar.baz').domain AS value",
                row -> Assert.assertThat(row.get("value"), equalTo("bar.baz")));
    }

    @Test
    public void testLocalEmail() {
        // Internet standards strongly discourage this possibility, but it's out there.
        testCall(db, "RETURN apoc.data.email('root@localhost').domain AS value",
        row -> Assert.assertThat(row.get("value"), equalTo("localhost")));
    }

    @Test
    public void testNull() {
        testCall(db, "RETURN apoc.data.email(null).domain AS value",
                row -> assertEquals(null, row.get("value")));
    }

    @Test
    public void testBadString() {
        testCall(db, "RETURN apoc.data.email('asdsgawe4ge').domain AS value",
                row -> assertEquals(null, row.get("value")));
    }

    @Test
    public void testEmailWithDotsBeforeAt() {
        testCall(db, "RETURN apoc.data.email('foo.foo@bar.baz').domain AS value",
                row -> Assert.assertThat(row.get("value"), equalTo("bar.baz")));
    }
}
