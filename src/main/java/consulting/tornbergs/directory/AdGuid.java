package consulting.tornbergs.directory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** Canonical GUID text to AD's binary objectGUID (first three fields little endian). */
final class AdGuid {
    static byte[] bytes(String text) {
        if (text==null || !text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new Api.Failure(400,"INVALID_IDENTIFIER","guid must be a canonical AD objectGUID, for example 00112233-4455-6677-8899-aabbccddeeff.");
        UUID uuid=UUID.fromString(text);
        long high=uuid.getMostSignificantBits();
        ByteBuffer bytes=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt((int)(high >>> 32)).putShort((short)(high >>> 16)).putShort((short)high);
        bytes.order(ByteOrder.BIG_ENDIAN).putLong(uuid.getLeastSignificantBits());
        return bytes.array();
    }
}
