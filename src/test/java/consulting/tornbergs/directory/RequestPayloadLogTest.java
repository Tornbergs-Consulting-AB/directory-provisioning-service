package consulting.tornbergs.directory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RequestPayloadLogTest {
 private final ObjectMapper mapper=new ObjectMapper();
 @Test void keepUnexpectedFields(){String s=RequestPayloadLog.render(mapper,"{\"unexpected\":\"value\",\"user\":{\"guid\":\"abc\"}}",1000);assertTrue(s.contains("unexpected"));assertTrue(s.contains("abc"));}
 @Test void redactNestedSecretsAndArrays(){String s=RequestPayloadLog.render(mapper,"{\"password\":\"DO_NOT_LOG\",\"nested\":[{\"client_secret\":\"DO_NOT_LOG\",\"Authorization\":\"DO_NOT_LOG\"}],\"allData\":\"DO_NOT_LOG\"}",1000);assertFalse(s.contains("DO_NOT_LOG"));assertTrue(s.contains("REDACTED"));}
 @Test void malformedInputNeverLogged(){String s=RequestPayloadLog.render(mapper,"{\"password\":\"DO_NOT_LOG",1000);assertTrue(s.contains("INVALID_JSON"));assertFalse(s.contains("DO_NOT_LOG"));}
 @Test void trailingJsonNeverLogged(){assertTrue(RequestPayloadLog.render(mapper,"{} {\"password\":\"secret\"}",1000).contains("INVALID_JSON"));}
 @Test void logInjectionEscaped(){String s=RequestPayloadLog.render(mapper,"{\"dn\":\"line1\\nline2\\r\\u001b\"}",1000);assertFalse(s.contains("\n"));assertFalse(s.contains("\r"));assertFalse(s.contains("\u001b"));}
 @Test void boundedOutput(){String s=RequestPayloadLog.render(mapper,"{\"dn\":\""+"x".repeat(1000)+"\"}",256);assertTrue(s.endsWith("[TRUNCATED]"));assertTrue(s.length()<300);}
 @Test void scalarBodyNotLogged(){assertEquals("[NON_OBJECT_JSON_OMITTED]",RequestPayloadLog.render(mapper,"\"secret\"",1000));}
}
