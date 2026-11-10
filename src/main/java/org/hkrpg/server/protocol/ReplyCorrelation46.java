package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * CN4.6 NetPacket.Serialize/Deserialize store PacketID in MsgProtoHead field 1. Async requests
 * await that ID as well as the response command. Only a matching synchronous reply inherits it.
 */
public final class ReplyCorrelation46 {
  private final ThreadLocal<Context> current = new ThreadLocal<>();
  // Explicitly used by paid requests; never evict a receipt and permit an old charge to repeat.
  private final java.util.Map<String, byte[]> responseBodies = new java.util.HashMap<>();

  private String responseKey() {
    var context = current.get();
    return context == null || context.response == 0 || context.header.length == 0 ? null
        : context.response + ":" + java.util.HexFormat.of().formatHex(context.header);
  }

  public boolean hasRequestIdentity() { return responseKey() != null; }

  public synchronized byte[] cachedResponseBody() {
    var body = responseBodies.get(responseKey());
    return body == null ? null : body.clone();
  }

  public synchronized boolean canCacheResponseBody() {
    String key = responseKey();
    return key != null && (responseBodies.containsKey(key) || responseBodies.size() < 256);
  }

  public synchronized void cacheResponseBody(byte[] body) {
    if (!canCacheResponseBody()) throw new IllegalStateException("Reply receipt capacity reached");
    responseBodies.putIfAbsent(responseKey(), body.clone());
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  private static final class Context {
    final int response;
    final byte[] header;
    boolean sent;

    Context(int response, byte[] header) {
      this.response = response;
      this.header = header;
    }
  }

  public Scope enter(ProtocolBridge bridge, int requestCommand, byte[] requestHeader) {
    var previous = current.get();
    int response = bridge.experimental() ? bridge.clientResponseCommand(requestCommand) : 0;
    byte[] header = response == 0 ? new byte[0] : replyHeader(requestHeader);
    // An inner headerless or unknown request must not borrow its outer request's identity.
    current.set(new Context(response, header));
    return () -> {
      if (previous == null) current.remove();
      else current.set(previous);
    };
  }

  /** Do not echo tracing, account or other client-authored header fields. */
  static byte[] replyHeader(byte[] header) {
    if (header == null || header.length > 4096)
      throw ProtocolException.diagnostic("Invalid request header size");
    var input = CodedInputStream.newInstance(header);
    boolean found = false;
    long id = 0;
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        if ((tag >>> 3) == 1) {
          if (tag != 8 || found) throw ProtocolException.diagnostic("Invalid request packet ID");
          found = true;
          id = input.readUInt64();
          if (Long.compareUnsigned(id, 0xffffffffL) > 0)
            throw ProtocolException.diagnostic("Request packet ID exceeds uint32");
        } else if (!input.skipField(tag)) {
          throw new ProtocolException("Invalid request header tag");
        }
      }
      if (!input.isAtEnd()) throw new ProtocolException("Truncated request header");
      if (id == 0) return new byte[0];
      var bytes = new ByteArrayOutputStream(6);
      var output = CodedOutputStream.newInstance(bytes);
      output.writeUInt32(1, (int) id);
      output.flush();
      return bytes.toByteArray();
    } catch (IOException invalid) {
      throw new ProtocolException("Malformed request header");
    }
  }

  /** Run after protocol conversion, so native and core-backed replies use the same command IDs. */
  public byte[] outbound(byte[] bytes) {
    var context = current.get();
    if (context == null || context.sent || context.header.length == 0) return bytes;
    var input = Unpooled.wrappedBuffer(bytes);
    var output = new ByteArrayOutputStream(bytes.length + context.header.length);
    try {
      while (input.isReadable()) {
        var frame = PacketCodec.decodeNext(input);
        if (frame == null) throw new ProtocolException("Truncated outgoing reply");
        byte[] header = frame.header();
        if (!context.sent && frame.cmdId() == context.response) {
          if (header.length == 0) header = context.header;
          context.sent = true;
        }
        output.writeBytes(PacketCodec.encode(frame.cmdId(), header, frame.body()));
      }
      return output.toByteArray();
    } finally {
      input.release();
    }
  }
}
