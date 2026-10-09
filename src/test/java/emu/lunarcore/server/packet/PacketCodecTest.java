package emu.lunarcore.server.packet;

import io.netty.buffer.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketCodecTest {
    @Test void roundTripsUnsignedOpcodeAndHeader() {
        var buf = Unpooled.wrappedBuffer(PacketCodec.encode(50000, new byte[]{1,2}, new byte[]{3,4,5}));
        try {
            var frame = PacketCodec.decodeNext(buf);
            assertEquals(50000, frame.cmdId());
            assertArrayEquals(new byte[]{3,4,5}, frame.body());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }
    @Test void readsConcatenatedFramesAndMatchesExistingPacketFormat() {
        var packet = new BasePacket(123);
        packet.setData(new byte[]{7,8});
        assertArrayEquals(packet.build(), PacketCodec.encode(123, new byte[0], new byte[]{7,8}));
        var buf = Unpooled.wrappedBuffer(packet.build(), new BasePacket(456).build());
        try {
            assertEquals(123, PacketCodec.decodeNext(buf).cmdId());
            assertEquals(456, PacketCodec.decodeNext(buf).cmdId());
        } finally { buf.release(); }
    }
    @Test void truncatedFrameDoesNotAllocateOrConsume() {
        byte[] frame = PacketCodec.encode(1, new byte[0], new byte[]{1,2,3});
        for (int size = 0; size < frame.length; size++) {
            var buf = Unpooled.wrappedBuffer(frame, 0, size);
            try {
                assertNull(PacketCodec.decodeNext(buf));
                assertEquals(0, buf.readerIndex());
            } finally { buf.release(); }
        }
    }
    @Test void rejectsNegativeAndHugeLengthsBeforeAllocation() {
        for (int length : new int[]{-1, Integer.MAX_VALUE, PacketCodec.MAX_BODY_SIZE + 1}) {
            var buf = Unpooled.wrappedBuffer(PacketCodec.encode(1, new byte[0], new byte[0]));
            try {
                buf.setInt(8, length);
                assertThrows(IllegalArgumentException.class, () -> PacketCodec.decodeNext(buf));
            } finally { buf.release(); }
        }
    }
    @Test void rejectsCorruptMagicAndTail() {
        var buf = Unpooled.wrappedBuffer(PacketCodec.encode(1, new byte[0], new byte[0]));
        try {
            buf.setInt(12, 0);
            assertThrows(IllegalArgumentException.class, () -> PacketCodec.decodeNext(buf));
            buf.setInt(0, 0);
            assertThrows(IllegalArgumentException.class, () -> PacketCodec.decodeNext(buf));
        } finally { buf.release(); }
    }
}
