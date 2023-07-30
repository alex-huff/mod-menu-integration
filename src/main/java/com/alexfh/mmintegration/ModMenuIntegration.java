package com.alexfh.mmintegration;

import com.alexfh.mmintegration.server.MMIServer;
import net.fabricmc.api.ClientModInitializer;


public
class ModMenuIntegration implements ClientModInitializer
{
    @Override
    public
    void onInitializeClient()
    {
        new MMIServer().start();
    }
}