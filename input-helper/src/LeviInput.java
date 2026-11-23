import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * LEVI DOCK input helper. Runs on the phone as the ADB shell user
 * (`app_process`), creates a virtual "LEVI Keyboard" and "LEVI Mouse" through
 * /dev/uhid, and replays HID reports that levi-host writes to stdin.
 *
 * stdin frames: [type u8][len u8][payload len bytes]
 *   type 1 = mouse report    (7 bytes: buttons, x i16 LE, y i16 LE, wheel i8, pan i8)
 *   type 2 = keyboard report (8 bytes: boot keyboard report)
 *   type 9 = keep-alive (ignored)
 * When stdin closes (host exits / link drops) the devices are destroyed.
 */
public final class LeviInput {

    private static final int UHID_DESTROY = 1;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    private static final int EVENT_SIZE = 4376; // sizeof(struct uhid_event)
    private static final int BUS_USB = 3;

    private static final byte[] KEYBOARD = bytes(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0);

    private static final byte[] MOUSE = bytes(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0);

    // Note: the Mi 3W's broken touch panel floods phantom touches that make Android
    // drop other pointers' events. Disabling it needs DISABLE_INPUT_DEVICE, which
    // the Android 11 shell does not hold; LEVI DOCK works around it in the app.

    public static void main(String[] args) throws Exception {
        RandomAccessFile keyboard = create("LEVI Keyboard", KEYBOARD, 0x4C4B);
        RandomAccessFile mouse = create("LEVI Mouse", MOUSE, 0x4C4D);
        drain(keyboard);
        drain(mouse);
        System.out.println("ready");
        System.out.flush();

        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 1024));
        byte[] buf = new byte[255];
        try {
            while (true) {
                int type = in.read();
                if (type < 0) break;
                int len = in.readUnsignedByte();
                in.readFully(buf, 0, len);
                if (type == 1) input(mouse, buf, len);
                else if (type == 2) input(keyboard, buf, len);
            }
        } catch (IOException e) {
            // levi-host went away (pipe closed): fall through and remove the devices.
        } finally {
            destroy(mouse);
            destroy(keyboard);
        }
    }

    /** Kernels before 3.16 (the Mi 3W runs 3.4) only know UHID_CREATE / UHID_INPUT. */
    private static boolean legacy = false;
    private static final int UHID_CREATE = 0;
    private static final int UHID_INPUT = 8;
    private static final java.util.List<ByteBuffer> keepAlive = new java.util.ArrayList<>();

    private static RandomAccessFile create(String name, byte[] descriptor, int product) throws Exception {
        if (!legacy) {
            RandomAccessFile f = new RandomAccessFile("/dev/uhid", "rw");
            ByteBuffer b = event(UHID_CREATE2);
            putPadded(b, name, 128);
            putPadded(b, "levi-dock", 64);
            putPadded(b, "", 64);
            b.putShort((short) descriptor.length);
            b.putShort((short) BUS_USB);
            b.putInt(0x1209);      // vendor (pid.codes test range)
            b.putInt(product);
            b.putInt(1);           // version
            b.putInt(0);           // country
            b.put(descriptor);
            try {
                f.write(b.array());
                return f;
            } catch (IOException e) {
                f.close();
                legacy = true;     // ENOTSUP: fall back to the original API
            }
        }
        // Legacy UHID_CREATE: the descriptor is passed by user-space pointer.
        ByteBuffer desc = ByteBuffer.allocateDirect(descriptor.length);
        desc.put(descriptor);
        keepAlive.add(desc);
        java.lang.reflect.Field addr = java.nio.Buffer.class.getDeclaredField("address");
        addr.setAccessible(true);
        long ptr = addr.getLong(desc);
        boolean is64 = System.getProperty("os.arch", "").contains("64");

        RandomAccessFile f = new RandomAccessFile("/dev/uhid", "rw");
        ByteBuffer b = event(UHID_CREATE);
        putPadded(b, name, 128);
        putPadded(b, "levi-dock", 64);
        putPadded(b, "", 64);
        if (is64) b.putLong(ptr); else b.putInt((int) ptr);
        b.putShort((short) descriptor.length);
        b.putShort((short) BUS_USB);
        b.putInt(0x1209);
        b.putInt(product);
        b.putInt(1);
        b.putInt(0);
        f.write(b.array());
        return f;
    }

    private static void input(RandomAccessFile f, byte[] data, int len) throws IOException {
        if (legacy) {
            ByteBuffer b = event(UHID_INPUT);
            b.put(data, 0, len);
            b.position(4 + 4096);
            b.putShort((short) len);
            f.write(b.array());
            return;
        }
        ByteBuffer b = event(UHID_INPUT2);
        b.putShort((short) len);
        b.put(data, 0, len);
        f.write(b.array());
    }

    private static void destroy(RandomAccessFile f) {
        try {
            f.write(event(UHID_DESTROY).array());
            f.close();
        } catch (IOException ignored) {
        }
    }

    /** The kernel queues UHID_START/OPEN events for us; read them so the queue never fills. */
    private static void drain(final RandomAccessFile f) {
        Thread t = new Thread(() -> {
            byte[] sink = new byte[EVENT_SIZE];
            try {
                while (f.read(sink) > 0) { /* discard */ }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static ByteBuffer event(int type) {
        ByteBuffer b = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(type);
        return b;
    }

    private static void putPadded(ByteBuffer b, String s, int size) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(raw.length, size - 1);
        b.put(raw, 0, n);
        b.position(b.position() + (size - n));
    }

    private static byte[] bytes(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }
}
