package org.guyuemochen;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class Rcon implements Closeable {

    final private ByteChannel channel;
    final private PacketReader reader;
    final private PacketWriter writer;
    private String password;

    private volatile int requestCounter;

    Rcon(final ByteChannel channel, final int readBufferCapacity,
         final int writeBufferCapacity, final PacketCodec codec) {
        this.channel = channel;

        reader = new PacketReader(channel::read, readBufferCapacity, codec);
        writer = new PacketWriter(channel::write, writeBufferCapacity, codec);
    }

    public static Rcon open(final SocketAddress remote) throws IOException {
        SocketChannel sc = SocketChannel.open(remote);
        sc.socket().setKeepAlive(true);

        Selector selector = Selector.open();
        sc.register(selector, SelectionKey.OP_CONNECT);
        selector.select(2000);

        return new RconBuilder().withChannel(sc).build();
    }

    public static Rcon open(final String hostname, final int port) throws IOException {
        return open(new InetSocketAddress(hostname, port));
    }

    public static RconBuilder newBuilder() {
        return new RconBuilder();
    }

    public boolean authenticate(final String password) throws IOException {
        Packet response;
        this.password = password;

        synchronized (this) {
            response = writeAndRead(PacketType.SERVERDATA_AUTH, password);

            // This works around a quirk in CS:GO where an empty SERVERDATA_RESPONSE_VALUE is sent before the SERVERDATA_AUTH_RESPONSE.
            if (response.type == PacketType.SERVERDATA_RESPONSE_VALUE) {
                response = read(response.requestId);
            }
        }
        if (response.type != PacketType.SERVERDATA_AUTH_RESPONSE) {
            throw new IOException("Invalid auth response type: " + response.type);
        }
        return response.isValid();
    }

    public void tryAuthenticate(final String password) throws IOException {
        if (!authenticate(password)) {
            throw new IOException("Authentication failed");
        }
    }

    public String listenOnly() throws IOException{
        return read(0).payload;
    }

    public String sendCommand(final String command) throws IOException {
        return writeAndReadALl(PacketType.SERVERDATA_EXECCOMMAND, command);
    }

    public String oldSendCommand(final String command)throws IOException {
        return writeAndRead(PacketType.SERVERDATA_EXECCOMMAND, command).payload;
    }

    private synchronized String writeAndReadALl(final int packetType, final String payload) throws IOException {
        final int requestId = requestCounter++;

        writer.write(new Packet(requestId, packetType, payload));
        writer.write(new Packet(requestId + 1, PacketType.SERVERDATA_RESPONSE_VALUE, ""));
        return readAll(requestId);
    }

    private synchronized Packet writeAndRead(final int packetType, final String payload) throws IOException {
        final int requestId = requestCounter++;

        writer.write(new Packet(requestId, packetType, payload));

        return read(requestId);
    }

    private synchronized String readAll(final int expectedRequestId) throws IOException {
        StringBuilder output = new StringBuilder();
        Packet response;
        do{
            response = reader.read();
            if (!response.isValid()) {
                throw new IOException("Invalid command response: " + response.payload);
            }
            if (response.requestId != expectedRequestId) break;
            output.append(response.payload);
        }while(response.type == PacketType.SERVERDATA_RESPONSE_VALUE);

        return output.toString();
    }

    private synchronized Packet read(final int expectedRequestId) throws IOException {
        final Packet response = reader.read();

        if (response.isValid() && response.requestId != expectedRequestId) {
            throw new IOException(String.format("Unexpected response id (%d -> %d)", expectedRequestId, response.requestId));
        }

        return response;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
