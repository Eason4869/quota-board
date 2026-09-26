import com.yusheng.quota.data.ImportCodec;
import com.yusheng.quota.data.QueryResult;
import com.yusheng.quota.net.Parsers;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression coverage for backup validation and Novita balance units. */
public class ReviewRegressionTest {
    private QueryResult novita(String raw) throws Exception {
        Method parse = Parsers.class.getDeclaredMethod("novita", JSONObject.class);
        parse.setAccessible(true);
        return (QueryResult) parse.invoke(Parsers.INSTANCE,
            new JSONObject().put("availableBalance", raw));
    }

    @Test public void fiveDollarBalanceUsesDocumentedUnit() throws Exception {
        assertEquals(5.0, novita("50000").getBalance().getAmount(), 0.000001);
    }

    @Test public void tenDollarBalanceUsesDocumentedUnit() throws Exception {
        assertEquals(10.0, novita("100000").getBalance().getAmount(), 0.000001);
    }

    @Test public void hundredDollarBalanceUsesDocumentedUnit() throws Exception {
        assertEquals(100.0, novita("1000000").getBalance().getAmount(), 0.000001);
    }

    @Test public void unrelatedJsonIsNotAnEmptyBackup() {
        assertThrows(RuntimeException.class, () -> ImportCodec.INSTANCE.decode("{}"));
    }

    @Test public void settingsOnlyJsonDoesNotEraseAccounts() {
        assertThrows(RuntimeException.class,
            () -> ImportCodec.INSTANCE.decode("{\"settings\":{\"darkMode\":\"dark\"}}"));
    }

    @Test public void duplicateAccountIdentifiersAreRejected() {
        String input = "{\"accounts\":["
            + "{\"id\":\"same\",\"templateId\":\"deepseek\",\"name\":\"one\"},"
            + "{\"id\":\"same\",\"templateId\":\"deepseek\",\"name\":\"two\"}]}";
        assertThrows(RuntimeException.class, () -> ImportCodec.INSTANCE.decode(input));
    }

    @Test public void explicitEmptyBackupRemainsSupported() {
        assertTrue(ImportCodec.INSTANCE.decode("{\"accounts\":[],\"settings\":{}}")
            .getFirst().isEmpty());
    }
}
