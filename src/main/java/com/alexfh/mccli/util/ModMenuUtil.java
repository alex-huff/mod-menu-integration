package com.alexfh.mccli.util;

import com.alexfh.mccli.mixin.ConfigScreenFactoriesAccessor;
import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.util.mod.Mod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public
class ModMenuUtil
{
    public static
    List<String> getModMenuConfigNames()
    {
        return ConfigScreenFactoriesAccessor.getConfigScreenFactories().keySet().stream()
            .map(modID -> ModMenu.MODS.get(modID).getName()).collect(Collectors.toList());
    }

    public static
    boolean openConfigScreenFromModName(String modName)
    {
        MinecraftClient minecraftClient = MinecraftClient.getInstance();
        Map.Entry<String, Mod> modIDEntry = ModMenu.MODS.entrySet().stream()
            .filter(entry -> entry.getValue().getName().equals(modName)).findFirst().orElse(null);
        if (modIDEntry == null)
        {
            return false;
        }
        String modID        = modIDEntry.getKey();
        Screen configScreen = ModMenu.getConfigScreen(modID, minecraftClient.currentScreen);
        if (configScreen == null)
        {
            return false;
        }
        minecraftClient.setScreen(configScreen);
        return true;
    }
}
