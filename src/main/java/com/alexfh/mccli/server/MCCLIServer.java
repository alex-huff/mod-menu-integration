package com.alexfh.mccli.server;

import com.alexfh.mccli.util.ModMenuUtil;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public
class MCCLIServer extends Thread
{
    private static final Path socketPath;

    static
    {
        String xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        Path   socketDir     = Path.of(xdgRuntimeDir == null ? System.getProperty("java.io.tmpdir") : xdgRuntimeDir);
        long   pid           = ProcessHandle.current().pid();
        socketPath = socketDir.resolve("mc-cli-ipc-" + pid + ".sock");
    }

    @Override
    public
    void run()
    {
        if (!this.tryDeleteSocket())
        {
            return;
        }
        this.startServer();
        this.tryDeleteSocket();
    }

    private
    boolean tryDeleteSocket()
    {
        try
        {
            Files.deleteIfExists(MCCLIServer.socketPath);
        }
        catch (IOException ignored)
        {
            return false;
        }
        return true;
    }

    private
    void startServer()
    {
        UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(MCCLIServer.socketPath);
        try (ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX))
        {
            serverChannel.bind(socketAddress);
            while (true)
            {
                try (SocketChannel channel = serverChannel.accept())
                {
                    this.handleClient(channel);
                }
            }
        }
        catch (IOException | ExecutionException | InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    private
    void handleClient(SocketChannel socketChannel) throws IOException, ExecutionException, InterruptedException
    {
        String[] message = this.readMessage(socketChannel);
        if (message.length < 1)
        {
            this.sendResponse(socketChannel, "empty message", false);
            return;
        }
        String messageType = message[0];
        switch (messageType)
        {
            case "set-fov":
                if (message.length != 2)
                {
                    this.sendResponse(socketChannel, "set-fov takes exactly 1 argument", false);
                    return;
                }
                String fovString = message[1];
                try
                {
                    int fov = Integer.parseInt(fovString);
                    MinecraftClient.getInstance()
                        .execute(() -> MinecraftClient.getInstance().options.getFov().setValue(fov));
                    this.sendResponse(socketChannel, "setting fov: " + fov, true);
                }
                catch (NumberFormatException ignored)
                {
                    this.sendResponse(socketChannel, "invalid fov: " + fovString, false);
                    return;
                }
                break;
            case "get-mod-names":
                if (message.length > 1)
                {
                    this.sendResponse(socketChannel, messageType + " takes no arguments", false);
                    return;
                }
                CompletableFuture<List<String>> modNamesFuture = new CompletableFuture<>();
                MinecraftClient.getInstance().execute(() -> modNamesFuture.complete(ModMenuUtil.getModMenuNames()));
                List<String> modNames = modNamesFuture.join();
                this.sendResponse(socketChannel, String.join("\n", modNames), true);
                break;
            case "open-config":
                if (message.length != 2)
                {
                    this.sendResponse(socketChannel, "open-config takes exactly 1 argument", false);
                    return;
                }
                String modName = message[1];
                CompletableFuture<Boolean> successFuture = new CompletableFuture<>();
                MinecraftClient.getInstance()
                    .execute(() -> successFuture.complete(ModMenuUtil.openConfigScreenFromModName(modName)));
                boolean success = successFuture.join();
                if (success)
                {
                    this.sendResponse(socketChannel, "opened " + modName + " config", true);
                }
                else
                {
                    this.sendResponse(socketChannel, modName + " has no config screen", false);
                }
                break;
            default:
                this.sendResponse(socketChannel, "invalid message type: " + messageType, false);
        }
    }

    public
    void sendResponse(SocketChannel socketChannel, String message, boolean success) throws IOException
    {
        this.sendVarInt(socketChannel, success ? 1 : 0);
        this.sendUTF8String(socketChannel, message);
    }

    public
    void sendUTF8String(SocketChannel socketChannel, String message) throws IOException
    {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        this.sendVarInt(socketChannel, messageBytes.length);
        this.sendAllBytes(socketChannel, messageBytes);
    }

    public
    void sendVarInt(SocketChannel socketChannel, int uInt) throws IOException
    {
        List<Byte> varIntBytes     = new ArrayList<>();
        int        continuationBit = 0x80;
        while (continuationBit > 0)
        {
            int lowSeven = uInt & 0x7F;
            uInt >>= 7;
            continuationBit = uInt > 0 ? continuationBit : 0;
            byte varIntByte = (byte) (continuationBit | lowSeven);
            varIntBytes.add(varIntByte);
        }
        byte[] primitiveVarIntBytes = new byte[varIntBytes.size()];
        for (int i = 0; i < primitiveVarIntBytes.length; i++)
        {
            primitiveVarIntBytes[i] = varIntBytes.get(i);
        }
        this.sendAllBytes(socketChannel, primitiveVarIntBytes);
    }

    public
    void sendAllBytes(SocketChannel socketChannel, byte[] bytes) throws IOException
    {
        if (socketChannel.write(ByteBuffer.wrap(bytes)) < bytes.length)
        {
            throw new IOException("could not write entire buffer");
        }
    }

    public
    String[] readMessage(SocketChannel socketChannel) throws IOException
    {
        int      messageLength = this.readVarInt(socketChannel);
        String[] message       = new String[messageLength];
        for (int i = 0; i < messageLength; i++)
        {
            message[i] = this.readUTF8String(socketChannel);
        }
        return message;
    }

    private
    String readUTF8String(SocketChannel socketChannel) throws IOException
    {
        int        stringBytesSize = this.readVarInt(socketChannel);
        ByteBuffer buffer          = ByteBuffer.allocate(stringBytesSize);
        this.readAllBytes(socketChannel, buffer);
        return new String(buffer.array(), StandardCharsets.UTF_8);
    }

    private
    int readVarInt(SocketChannel socketChannel) throws IOException
    {
        int        continuationBit  = 0x80;
        int        varInt           = 0;
        int        bytesProcessed   = 0;
        ByteBuffer singleByteBuffer = ByteBuffer.allocate(1);
        while (continuationBit > 0)
        {
            this.readAllBytes(socketChannel, singleByteBuffer);
            singleByteBuffer.flip();
            int varIntByte = singleByteBuffer.get();
            singleByteBuffer.clear();
            continuationBit = varIntByte & continuationBit;
            varIntByte      = varIntByte & 0x7F;
            varInt |= varIntByte << (7 * bytesProcessed);
            bytesProcessed++;
        }
        return varInt;
    }

    private
    void readAllBytes(SocketChannel socketChannel, ByteBuffer buffer) throws IOException
    {
        while (socketChannel.read(buffer) > 0)
        {
        }
        if (buffer.remaining() > 0)
        {
            throw new IOException("could not fill entire buffer");
        }
    }

}
