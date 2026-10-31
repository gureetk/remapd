package ca.gureet.remapd.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

public class AdbMessageTest {
    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** The bytes adb sends to connect, written out by hand from protocol.txt. */
    @Test
    public void connectPacketBytes() {
        byte[] payload = "host::\0".getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = new AdbMessage(AdbMessage.A_CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD,
                payload).encode();
        byte[] header = hex("434e584e" + "01000001" + "00001000" + "07000000"
                + "32020000"       // 0x232 = 562: the sum of the bytes of "host::\0"
                + "bcb1a7b1");     // ~CNXN
        assertArrayEquals(header, Arrays.copyOf(encoded, 24));
        assertArrayEquals(payload, Arrays.copyOfRange(encoded, 24, encoded.length));
        assertEquals("CNXN", AdbMessage.name(AdbMessage.A_CNXN));
    }

    @Test
    public void roundTrip() throws IOException {
        byte[] payload = {1, 2, 3, (byte) 0xff};
        AdbMessage m = AdbMessage.read(new ByteArrayInputStream(
                new AdbMessage(AdbMessage.A_WRTE, 7, 9, payload).encode()));
        assertEquals(AdbMessage.A_WRTE, m.command);
        assertEquals(7, m.arg0);
        assertEquals(9, m.arg1);
        assertArrayEquals(payload, m.payload);
    }

    @Test
    public void rejectsBadMagic() {
        byte[] bytes = new AdbMessage(AdbMessage.A_OKAY, 1, 2, AdbMessage.EMPTY).encode();
        bytes[20] ^= 1;
        try {
            AdbMessage.read(new ByteArrayInputStream(bytes));
            fail("read a packet with a bad header");
        } catch (IOException expected) {
        }
    }

    @Test
    public void rejectsHugePayloads() {
        byte[] bytes = new AdbMessage(AdbMessage.A_WRTE, 1, 2, AdbMessage.EMPTY).encode();
        bytes[12] = 0;
        bytes[13] = 0;
        bytes[14] = 0x20;   // 2 MB
        try {
            AdbMessage.read(new ByteArrayInputStream(bytes));
            fail("accepted a 2 MB payload");
        } catch (IOException expected) {
        }
    }

    @Test(expected = EOFException.class)
    public void truncatedPacket() throws IOException {
        byte[] bytes = new AdbMessage(AdbMessage.A_WRTE, 1, 2, new byte[10]).encode();
        AdbMessage.read(new ByteArrayInputStream(Arrays.copyOf(bytes, 30)));
    }
}
