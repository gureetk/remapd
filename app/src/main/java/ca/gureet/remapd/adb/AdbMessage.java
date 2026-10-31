package ca.gureet.remapd.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One adb packet: a 24-byte little-endian header and a payload. The format is
 * described in protocol.txt in AOSP's adb sources.
 */
final class AdbMessage {
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_STLS = 0x534c5453;

    /** A_VERSION: the protocol version that stopped checking payload checksums. */
    static final int VERSION = 0x01000001;
    static final int STLS_VERSION = 0x01000000;
    /** The largest payload this client accepts (adb's MAX_PAYLOAD). */
    static final int MAX_PAYLOAD = 1024 * 1024;

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static final int HEADER_SIZE = 24;
    static final byte[] EMPTY = new byte[0];

    final int command;
    final int arg0;
    final int arg1;
    final byte[] payload;

    AdbMessage(int command, int arg0, int arg1, byte[] payload) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.payload = payload;
    }

    /** The header and payload, ready to write in one go. */
    byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length)
                .putInt(checksum(payload)).putInt(~command);
        b.put(payload);
        return b.array();
    }

    /** Reads exactly one packet. Throws EOFException if the connection closes first. */
    static AdbMessage read(InputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(readFully(in, HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        int length = b.getInt();
        b.getInt();   // the checksum, which adb no longer checks (VERSION above)
        int magic = b.getInt();
        if (magic != ~command) {
            throw new IOException("not an adb packet (bad header)");
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("adb packet too large: " + (length & 0xffffffffL) + " bytes");
        }
        return new AdbMessage(command, arg0, arg1, readFully(in, length));
    }

    /** The sum of the payload bytes, which older adb versions check. */
    static int checksum(byte[] data) {
        int sum = 0;
        for (byte x : data) {
            sum += x & 0xff;
        }
        return sum;
    }

    static String name(int command) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            int ch = (command >>> (8 * i)) & 0xff;
            c[i] = ch >= 0x20 && ch < 0x7f ? (char) ch : '?';
        }
        return new String(c);
    }

    @Override
    public String toString() {
        return name(command) + "(" + arg0 + ", " + arg1 + ", " + payload.length + " bytes)";
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int done = 0;
        while (done < length) {
            int n = in.read(buf, done, length - done);
            if (n < 0) {
                throw new EOFException("adb closed the connection");
            }
            done += n;
        }
        return buf;
    }
}
