package emu.lunarcore.server.packet;

import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;

/** Validates framing before allocating a client-controlled payload. */
public final class PacketCodec {
    public static final int MAX_BODY_SIZE = 8 * 1024 * 1024;
    public record Frame(int cmdId, byte[] header, byte[] body) {}

    public static Frame decodeNext(ByteBuf input) {
        if (input.readableBytes() < 16) return null;
        int offset = input.readerIndex();
        if (input.getInt(offset) != BasePacket.HEADER_CONST) throw new IllegalArgumentException("Invalid packet magic");
        int cmdId = input.getUnsignedShort(offset + 4);
        int headerLength = input.getUnsignedShort(offset + 6);
        int bodyLength = input.getInt(offset + 8);
        if (bodyLength < 0 || bodyLength > MAX_BODY_SIZE) throw new IllegalArgumentException("Invalid packet body length: " + bodyLength);
        int frameLength = 16 + headerLength + bodyLength;
        if (input.readableBytes() < frameLength) return null;
        if (input.getInt(offset + frameLength - 4) != BasePacket.TAIL_CONST) throw new IllegalArgumentException("Invalid packet tail");
        byte[] body = new byte[bodyLength];
        byte[] header = new byte[headerLength];
        input.skipBytes(12);
        input.readBytes(header);
        input.readBytes(body);
        input.skipBytes(4);
        return new Frame(cmdId, header, body);
    }

    public static byte[] encode(int cmdId, byte[] header, byte[] body) {
        if (cmdId < 0 || cmdId > 65535) throw new IllegalArgumentException("Opcode out of range");
        if (header.length > 65535 || body.length > MAX_BODY_SIZE) throw new IllegalArgumentException("Packet too large");
        return ByteBuffer.allocate(16 + header.length + body.length)
            .putInt(BasePacket.HEADER_CONST).putShort((short) cmdId).putShort((short) header.length)
            .putInt(body.length).put(header).put(body).putInt(BasePacket.TAIL_CONST).array();
    }
    private PacketCodec() {}
}
