package consulting.tornbergs.directory;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.Locale;

/** Diagnostic rendering only: never log malformed raw input or HTTP headers. */
final class RequestPayloadLog {
    static String render(ObjectMapper mapper,String raw,int maxChars) {
        String rendered;
        try {
            JsonNode node=mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(raw==null?"":raw);
            if (node==null || !node.isObject()) return "[NON_OBJECT_JSON_OMITTED]";
            redact(node);
            rendered=mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "[INVALID_JSON_OMITTED chars="+(raw==null?0:raw.length())+"]";
        }
        int limit=Math.max(256,Math.min(65536,maxChars));
        return rendered.length()>limit?rendered.substring(0,limit)+" [TRUNCATED]":rendered;
    }
    private static void redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object=(ObjectNode)node;
            var fields=object.fields();
            while (fields.hasNext()) {
                var field=fields.next();
                String key=field.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
                if (key.contains("password") || key.contains("passwd") || key.equals("pwd")
                    || key.contains("secret") || key.contains("token") || key.contains("credential")
                    || key.contains("authorization") || key.equals("cookie") || key.equals("cookies")
                    || key.contains("apikey") || key.contains("privatekey") || key.equals("alldata")
                    || key.equals("headers"))
                    object.set(field.getKey(),TextNode.valueOf("[REDACTED]"));
                else redact(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child:node) redact(child);
        }
    }
}
