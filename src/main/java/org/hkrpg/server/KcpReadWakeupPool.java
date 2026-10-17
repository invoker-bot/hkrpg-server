package org.hkrpg.server;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import kcp.highway.ReadTask;
import kcp.highway.Ukcp;
import kcp.highway.threadPool.IMessageExecutor;
import kcp.highway.threadPool.IMessageExecutorPool;
import kcp.highway.threadPool.ITask;
import kcp.highway.threadPool.netty.NettyMessageExecutorPool;

/** Resumes input queued while the pinned KCP reader is delivering application callbacks. */
public final class KcpReadWakeupPool implements IMessageExecutorPool {
  // Like TransportStartup, this adapter deliberately checks the bundled dependency's layout.
  // ReadTask clears readProcessing after callbacks without checking for newly queued input.
  private static final VarHandle CONNECTION = field(ReadTask.class, "ukcp", Ukcp.class);
  private static final VarHandle QUEUE = field(Ukcp.class, "readBuffer", Queue.class);
  private static final VarHandle PROCESSING = field(Ukcp.class, "readProcessing", AtomicBoolean.class);
  private final IMessageExecutorPool delegate;

  public KcpReadWakeupPool(int threads) {
    delegate = new NettyMessageExecutorPool(threads);
  }

  @Override
  public IMessageExecutor getIMessageExecutor() {
    return new ReaderExecutor(delegate.getIMessageExecutor());
  }

  @Override
  public void stop() {
    delegate.stop();
  }

  private static final class ReaderExecutor implements IMessageExecutor {
    private final IMessageExecutor delegate;

    ReaderExecutor(IMessageExecutor delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(ITask task) {
      if (!(task instanceof ReadTask reader)) {
        delegate.execute(task);
        return;
      }
      var connection = (Ukcp) CONNECTION.get(reader);
      delegate.execute(() -> {
        reader.execute();
        if (!connection.isActive()) return;
        var pending = (Queue<?>) QUEUE.get(connection);
        var processing = (AtomicBoolean) PROCESSING.get(connection);
        // Claim the same flag used by Ukcp.read: an arrival that already scheduled a reader
        // wins instead. Requeue on the serial executor so other connections retain their turn.
        if (!pending.isEmpty() && processing.compareAndSet(false, true)) {
          try { execute(reader); }
          catch (RuntimeException rejected) {
            processing.compareAndSet(true, false);
            throw rejected;
          }
        }
      });
    }

    @Override
    public void stop() {
      delegate.stop();
    }

    @Override
    public boolean isFull() {
      return delegate.isFull();
    }
  }

  private static VarHandle field(Class<?> owner, String name, Class<?> type) {
    try {
      return MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
          .findVarHandle(owner, name, type);
    } catch (ReflectiveOperationException incompatible) {
      throw new ExceptionInInitializerError(incompatible);
    }
  }
}
