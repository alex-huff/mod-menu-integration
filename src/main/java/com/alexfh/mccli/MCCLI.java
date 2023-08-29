package com.alexfh.mccli;

import com.alexfh.mccli.server.MCCLIServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;


public
class MCCLI implements ClientModInitializer
{
    @Override
    public
    void onInitializeClient()
    {
        MCCLIServer mccliServer = new MCCLIServer();
        if (mccliServer.initServer())
        {
            mccliServer.start();
            ClientLifecycleEvents.CLIENT_STOPPING.register((client) -> mccliServer.shutdown());
        }
    }
}