package com.alexfh.mmintegration;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModMenuIntegration implements ClientModInitializer
{
    public static final Logger LOGGER = LoggerFactory.getLogger("mod-menu-integration");

	@Override
	public
	void onInitializeClient()
	{
		ModMenuIntegration.LOGGER.info("hi");
	}
}