package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;

/**
 * Guards the Win32 structure layout used by {@link ImePositioner}.
 *
 * <p>{@code CANDIDATEFORM} starts with {@code dwIndex}; when the diagnostics
 * declared it without that field every read-back was shifted by four bytes and
 * reported a default candidate position that imm32 never held. These assertions
 * pin the layout to the MSVC one so the mistake cannot come back unnoticed.
 */
public class ImePositioningLayoutTest {

    private static ByteBuffer bytes(com.sun.jna.Structure s) {
        s.write();
        byte[] raw = s.getPointer().getByteArray(0, s.size());
        return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    public void candidateFormMatchesMsvcLayout() {
        ImePositioner.CANDIDATEFORM f = new ImePositioner.CANDIDATEFORM();
        f.dwIndex = 0x11223344;
        f.dwStyle = 0x55667788;
        f.ptCurrentPos.x = 0x0A0B0C0D;
        f.ptCurrentPos.y = 0x01020304;
        f.rcArea.left = 11;
        f.rcArea.top = 12;
        f.rcArea.right = 13;
        f.rcArea.bottom = 14;

        ByteBuffer b = bytes(f);
        // DWORD dwIndex, DWORD dwStyle, POINT, RECT -- no padding on x64.
        assertEquals(32, f.size());
        assertEquals(0x11223344, b.getInt(0));
        assertEquals(0x55667788, b.getInt(4));
        assertEquals(0x0A0B0C0D, b.getInt(8));
        assertEquals(0x01020304, b.getInt(12));
        assertEquals(11, b.getInt(16));
        assertEquals(12, b.getInt(20));
        assertEquals(13, b.getInt(24));
        assertEquals(14, b.getInt(28));
    }

    @Test
    public void compositionFormMatchesMsvcLayout() {
        ImePositioner.COMPOSITIONFORM f = new ImePositioner.COMPOSITIONFORM();
        f.dwStyle = 0x00000001;
        f.ptCurrentPos.x = 21;
        f.ptCurrentPos.y = 22;
        f.rcArea.left = 23;
        f.rcArea.top = 24;
        f.rcArea.right = 25;
        f.rcArea.bottom = 26;

        ByteBuffer b = bytes(f);
        // DWORD dwStyle, POINT at offset 4, RECT at offset 12.
        assertEquals(28, f.size());
        assertEquals(1, b.getInt(0));
        assertEquals(21, b.getInt(4));
        assertEquals(22, b.getInt(8));
        assertEquals(23, b.getInt(12));
        assertEquals(24, b.getInt(16));
        assertEquals(25, b.getInt(20));
        assertEquals(26, b.getInt(24));
    }

    @Test
    public void pointAndRectAreUnpadded() {
        assertEquals(8, new ImePositioner.POINT().size());
        assertEquals(16, new ImePositioner.RECT().size());
    }
}
