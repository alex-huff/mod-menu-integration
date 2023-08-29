package com.alexfh.mccli;

import com.alexfh.mccli.server.MCCLIServer;
import net.fabricmc.api.ClientModInitializer;


public
class MCCLI implements ClientModInitializer
{
    @Override
    public
    void onInitializeClient()
    {
        new MCCLIServer().start();
    }
}