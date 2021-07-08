package apoc.number;

import apoc.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import static apoc.util.TestUtil.testCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * @author inserpio
 * @since 25.8.2016
 */
public class NumbersTest {

    @Rule
    public ExpectedException expected = ExpectedException.none();

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule();

    @BeforeClass
    public static void sUp() throws Exception {
        TestUtil.registerProcedure(db, Numbers.class);
    }

    @Test
    public void testFormat() throws Exception {
        testCall(db, "RETURN apoc.number.format(12345) AS value", row -> assertEquals("12,345", row.get("value")));
        testCall(db, "RETURN apoc.number.format('aaa') AS value", row -> assertEquals(null, row.get("value")));
        testCall(db, "RETURN apoc.number.format(12345, '', 'it') AS value", row -> assertEquals("12.345", row.get("value")));
        testCall(db, "RETURN apoc.number.format(12345, '', 'apoc') AS value", row -> assertEquals(null, row.get("value")));
        testCall(db, "RETURN apoc.number.format(12345, '#,##0.00;(#,##0.00)') AS value", row -> assertEquals("12,345.00", row.get("value")));
        testCall(db, "RETURN apoc.number.format(12345, '#,##0.00;(#,##0.00)', 'it') AS value", row -> assertEquals("12.345,00", row.get("value")));
        testCall(db, "RETURN apoc.number.format(12345.67) AS value", row -> assertEquals("12,345.67", row.get("value")));
        testCall(db, "RETURN apoc.number.format(12345.67, '', 'it') AS value", row -> assertEquals("12.345,67", row.get("value")));
        testCall(db, "RETURN apoc.number.format(12345.67, '#,##0.00;(#,##0.00)') AS value", row -> assertEquals("12,345.67", row.get("value")));
        testCall(db, "RETURN apoc.number.format(12345.67, '#,##0.00;(#,##0.00)', 'it') AS value", row -> assertEquals("12.345,67", row.get("value")));
    }

    @Test
    public void testParseInt() throws Exception {
        testCall(db, "RETURN apoc.number.parseInt('12,345') AS value", row -> assertEquals(new Long(12345), row.get("value")));
        testCall(db, "RETURN apoc.number.parseInt('12.345', '' ,'it') AS value", row -> assertEquals(new Long(12345), row.get("value")));
        testCall(db, "RETURN apoc.number.parseInt('12,345', '#,##0.00;(#,##0.00)') AS value", row -> assertEquals(new Long(12345), row.get("value")));
        testCall(db, "RETURN apoc.number.parseInt('12.345', '#,##0.00;(#,##0.00)', 'it') AS value", row -> assertEquals(new Long(12345), row.get("value")));
        testCall(db, "RETURN apoc.number.parseInt('aaa') AS value", row -> assertEquals(null, row.get("value")));
        testCall(db, "RETURN apoc.number.parseInt(null) AS value", row -> assertNull(row.get("value")));
    }

    // Parse Double

    @Test
    public void testParseFloat() throws Exception {
        testCall(db, "RETURN apoc.number.parseFloat('12,345.67') AS value", row -> assertEquals(new Double(12345.67), row.get("value")));
        testCall(db, "RETURN apoc.number.parseFloat('12.345,67', '', 'it') AS value", row -> assertEquals(new Double(12345.67), row.get("value")));
        testCall(db, "RETURN apoc.number.parseFloat('12,345.67', '#,##0.00;(#,##0.00)') AS value", row -> assertEquals(new Double(12345.67), row.get("value")));
        testCall(db, "RETURN apoc.number.parseFloat('12.345,67', '#,##0.00;(#,##0.00)', 'it') AS value", row -> assertEquals(new Double(12345.67), row.get("value")));
        testCall(db, "RETURN apoc.number.parseFloat('aaa') AS value", row -> assertEquals(null, row.get("value")));
        testCall(db, "RETURN apoc.number.parseFloat(null) AS value", row -> assertNull(row.get("value")));
    }
}
