package com.rinha.server.nio;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Estado per-conexão. Vive enquanto a conexão keep-alive existir.
 * Read buffer heap (2 KiB) — o overhead de cópia interna do JDK é
 * compensado por podermos parsear direto sobre o {@code byte[]}
 * subjacente (e pelo body típico ~400 B caber num single read).
 */
final class Connection {

    static final int READ_BUF_SIZE = 2048;

    final SocketChannel channel;
    final SelectionKey key;
    final ByteBuffer readBuf;
    final HttpRequestParser parser;

    // write pendente (ainda não totalmente escrito no socket)
    byte[] pendingWrite;
    int pendingOffset;
    boolean closeAfterWrite;

    Connection(SocketChannel channel, SelectionKey key) {
        this.channel = channel;
        this.key = key;
        this.readBuf = ByteBuffer.allocate(READ_BUF_SIZE);
        this.parser = new HttpRequestParser();
    }

    boolean hasPendingWrite() {
        return pendingWrite != null && pendingOffset < pendingWrite.length;
    }
}
