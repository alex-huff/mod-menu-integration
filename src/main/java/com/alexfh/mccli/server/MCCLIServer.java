package com.alexfh.mccli.server;

import com.alexfh.mccli.util.ModMenuUtil;
import com.terraformersmc.modmenu.ModMenu;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MCCLIServer extends Thread
{

    private static class ClientClosedSocketException extends IOException
    {

    }

    private static final Path socketPath;

    static
    {
        String xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        Path socketDir = Path.of(xdgRuntimeDir == null ? System.getProperty("java.io.tmpdir") : xdgRuntimeDir);
        long pid = ProcessHandle.current().pid();
        socketPath = socketDir.resolve("mc-cli-ipc-" + pid + ".sock");
    }

    private ServerSocketChannel serverChannel = null;

    @Override
    public void run()
    {
        this.runServer();
        this.tryDeleteSocket();
    }

    public void shutdown()
    {
        this.interrupt();
        this.tryCloseChannel();
        try
        {
            this.join();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
        this.tryDeleteSocket();
    }

    private boolean tryDeleteSocket()
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

    private void tryCloseChannel()
    {
        try
        {
            this.serverChannel.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public boolean initServer()
    {
        if (!this.tryDeleteSocket())
        {
            return false;
        }
        UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(MCCLIServer.socketPath);
        try
        {
            this.serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            this.serverChannel.bind(socketAddress);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private void runServer()
    {
        while (!this.isInterrupted())
        {
            try (SocketChannel channel = this.serverChannel.accept())
            {
                this.handleClient(channel);
            }
            catch (ClosedChannelException ignored)
            {
                break;
            }
            catch (IOException e)
            {
                e.printStackTrace();
                break;
            }
        }
        this.tryCloseChannel();
    }

    private void handleClient(SocketChannel socketChannel) throws IOException
    {
        while (true)
        {
            try
            {
                this.handleMessage(socketChannel);
            }
            catch (ClientClosedSocketException ignored)
            {
                break;
            }
        }
    }

    private boolean verifyNumArguments(SocketChannel socketChannel, String messageType, String[] message, int num)
        throws IOException
    {
        return this.verifyNumArguments(socketChannel, messageType, message, num, num);
    }

    private boolean verifyNumArguments(SocketChannel socketChannel, String messageType, String[] message, int min,
                                       int max) throws IOException
    {
        int numArguments = message.length - 1;
        if (numArguments < min || numArguments > max)
        {
            String expectedNumArgumentsMessage = min == max ? min + " arguments"
                                                            : "between " + min + " and " + max + " " + "arguments";
            this.sendResponse(socketChannel, messageType + " takes " + expectedNumArgumentsMessage, false);
            return false;
        }
        return true;
    }

    private void handleMessage(SocketChannel socketChannel) throws IOException
    {
        String[] message = this.readMessage(socketChannel);
        if (message.length < 1)
        {
            this.sendResponse(socketChannel, "empty message", false);
            return;
        }
        String messageType = message[0];
        boolean numArgumentsValid = true;
        switch (messageType)
        {
            case "ping", "get-username", "get-server-ip", "get-config-names", "get-mods" ->
                numArgumentsValid = this.verifyNumArguments(socketChannel, messageType, message, 0);
            case "set-fov", "set-brightness", "open-config" ->
                numArgumentsValid = this.verifyNumArguments(socketChannel, messageType, message, 1);
            case "send" -> numArgumentsValid = this.verifyNumArguments(socketChannel, messageType, message, 2);
        }
        if (!numArgumentsValid)
        {
            return;
        }
        switch (messageType)
        {
            case "ping" -> this.sendResponse(socketChannel, "pong", true);
            case "get-username" ->
                this.sendResponse(socketChannel, MinecraftClient.getInstance().getSession().getUsername(), true);
            case "get-server-ip" ->
            {
                CompletableFuture<String> addressFuture = new CompletableFuture<>();
                MinecraftClient.getInstance().execute(() ->
                {
                    ServerInfo serverInfo = MinecraftClient.getInstance().getCurrentServerEntry();
                    String address = serverInfo == null ? null : serverInfo.address;
                    addressFuture.complete(address);
                });
                String address = addressFuture.join();
                if (address != null)
                {
                    this.sendResponse(socketChannel, address, true);
                }
                else
                {
                    this.sendResponse(socketChannel, "not connected to server", false);
                }
            }
            case "get-config-names" ->
            {
                CompletableFuture<List<String>> configNamesFuture = new CompletableFuture<>();
                MinecraftClient.getInstance()
                    .execute(() -> configNamesFuture.complete(ModMenuUtil.getModMenuConfigNames()));
                List<String> configNames = configNamesFuture.join();
                this.sendResponse(socketChannel, String.join("\n", configNames), true);
            }
            case "get-mods" ->
            {
                CompletableFuture<List<String>> modsFuture = new CompletableFuture<>();
                MinecraftClient.getInstance().execute(() -> modsFuture.complete(ModMenu.MODS.values().stream()
                    .map(mod -> mod.getName() + "\t" + mod.getVersion()).toList()));
                List<String> mods = modsFuture.join();
                this.sendResponse(socketChannel, String.join("\n", mods), true);
            }
            case "set-fov" ->
            {
                String fovString = message[1];
                try
                {
                    int fov = Integer.parseInt(fovString);
                    CompletableFuture<Integer> fovFuture = new CompletableFuture<>();
                    MinecraftClient.getInstance().execute(() ->
                    {
                        SimpleOption<Integer> fovOption = MinecraftClient.getInstance().options.getFov();
                        fovOption.setValue(fov);
                        fovFuture.complete(fovOption.getValue());
                    });
                    this.sendResponse(socketChannel, "set fov: " + fovFuture.join(), true);
                }
                catch (NumberFormatException ignored)
                {
                    this.sendResponse(socketChannel, "invalid fov: " + fovString, false);
                }
            }
            case "set-brightness" ->
            {
                String brightnessString = message[1];
                try
                {
                    double brightness = Double.parseDouble(brightnessString);
                    CompletableFuture<Double> brightnessFuture = new CompletableFuture<>();
                    MinecraftClient.getInstance().execute(() ->
                    {
                        SimpleOption<Double> brightnessOption = MinecraftClient.getInstance().options.getGamma();
                        brightnessOption.setValue(brightness);
                        brightnessFuture.complete(brightnessOption.getValue());
                    });
                    this.sendResponse(socketChannel, "set brightness: " + brightnessFuture.join(), true);
                }
                catch (NumberFormatException ignored)
                {
                    this.sendResponse(socketChannel, "invalid brightness: " + brightnessString, false);
                }
            }
            case "set-volume" ->
            {
                String volumeString = message[1];
                try
                {
                    double volume = Double.parseDouble(volumeString);
                    CompletableFuture<Double> volumeFuture = new CompletableFuture<>();
                    MinecraftClient.getInstance().execute(() ->
                    {
                        SimpleOption<Double> volumeOption
                            = MinecraftClient.getInstance().options.getSoundVolumeOption(SoundCategory.MASTER);
                        volumeOption.setValue(volume);
                        volumeFuture.complete(volumeOption.getValue());
                    });
                    this.sendResponse(socketChannel, "set volume: " + volumeFuture.join(), true);
                }
                catch (NumberFormatException ignored)
                {
                    this.sendResponse(socketChannel, "invalid volume: " + volumeString, false);
                }
            }
            case "open-config" ->
            {
                String configName = message[1];
                CompletableFuture<Boolean> configSuccessFuture = new CompletableFuture<>();
                MinecraftClient.getInstance()
                    .execute(() -> configSuccessFuture.complete(ModMenuUtil.openConfigScreenFromModName(configName)));
                boolean configSuccess = configSuccessFuture.join();
                if (configSuccess)
                {
                    this.sendResponse(socketChannel, "opened " + configName + " config", true);
                }
                else
                {
                    this.sendResponse(socketChannel, configName + " has no config screen", false);
                }
            }
            case "send" ->
            {
                String sendType = message[1];
                Boolean isCommand;
                switch (sendType)
                {
                    case "chat", "chat-local" -> isCommand = false;
                    case "command" -> isCommand = true;
                    default ->
                    {
                        isCommand = null;
                        this.sendResponse(socketChannel, "invalid send type", false);
                    }
                }
                if (isCommand == null)
                {
                    break;
                }
                String messageText = message[2];
                CompletableFuture<Boolean> messageSuccessFuture = new CompletableFuture<>();
                MinecraftClient.getInstance().execute(() ->
                {
                    if (sendType.equals("chat-local"))
                    {
                        ClientPlayerEntity player = MinecraftClient.getInstance().player;
                        boolean canSend = player != null;
                        if (canSend)
                        {
                            player.sendMessage(Text.of(messageText), true);
                        }
                        messageSuccessFuture.complete(canSend);
                        return;
                    }
                    ClientPlayNetworkHandler networkHandler = MinecraftClient.getInstance().getNetworkHandler();
                    if (networkHandler == null)
                    {
                        messageSuccessFuture.complete(false);
                        return;
                    }
                    if (isCommand)
                    {
                        networkHandler.sendChatCommand(messageText);
                    }
                    else
                    {
                        networkHandler.sendChatMessage(messageText);
                    }
                    messageSuccessFuture.complete(true);
                });
                boolean messageSuccess = messageSuccessFuture.join();
                if (messageSuccess)
                {
                    this.sendResponse(socketChannel, "sent", true);
                }
                else
                {
                    this.sendResponse(socketChannel, "failed to send", false);
                }
            }
            default -> this.sendResponse(socketChannel, "invalid message type: " + messageType, false);
        }
    }

    public void sendResponse(SocketChannel socketChannel, String message, boolean success) throws IOException
    {
        this.sendVarInt(socketChannel, success ? 1 : 0);
        this.sendUTF8String(socketChannel, message);
    }

    public void sendUTF8String(SocketChannel socketChannel, String message) throws IOException
    {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        this.sendVarInt(socketChannel, messageBytes.length);
        this.sendAllBytes(socketChannel, messageBytes);
    }

    public void sendVarInt(SocketChannel socketChannel, int uInt) throws IOException
    {
        List<Byte> varIntBytes = new ArrayList<>();
        int continuationBit = 0x80;
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

    public void sendAllBytes(SocketChannel socketChannel, byte[] bytes) throws IOException
    {
        if (socketChannel.write(ByteBuffer.wrap(bytes)) < bytes.length)
        {
            throw new IOException("could not write entire buffer");
        }
    }

    public String[] readMessage(SocketChannel socketChannel) throws IOException
    {
        int messageLength = this.readVarInt(socketChannel);
        String[] message = new String[messageLength];
        for (int i = 0; i < messageLength; i++)
        {
            message[i] = this.readUTF8String(socketChannel);
        }
        return message;
    }

    private String readUTF8String(SocketChannel socketChannel) throws IOException
    {
        int stringBytesSize = this.readVarInt(socketChannel);
        ByteBuffer buffer = ByteBuffer.allocate(stringBytesSize);
        this.readAllBytes(socketChannel, buffer);
        return new String(buffer.array(), StandardCharsets.UTF_8);
    }

    private int readVarInt(SocketChannel socketChannel) throws IOException
    {
        int continuationBit = 0x80;
        int varInt = 0;
        int bytesProcessed = 0;
        ByteBuffer singleByteBuffer = ByteBuffer.allocate(1);
        while (continuationBit > 0)
        {
            this.readAllBytes(socketChannel, singleByteBuffer);
            singleByteBuffer.flip();
            int varIntByte = singleByteBuffer.get();
            singleByteBuffer.clear();
            continuationBit = varIntByte & continuationBit;
            varIntByte = varIntByte & 0x7F;
            varInt |= varIntByte << (7 * bytesProcessed);
            bytesProcessed++;
        }
        return varInt;
    }

    private void readAllBytes(SocketChannel socketChannel, ByteBuffer buffer) throws IOException
    {
        while (socketChannel.read(buffer) > 0)
        {
        }
        if (buffer.remaining() > 0)
        {
            throw new ClientClosedSocketException();
        }
    }

}
