package com.alexfh.mmintegration.mixin;

import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ModMenu.class)
public
interface ConfigScreenFactoriesAccessor
{
    @Accessor("configScreenFactories")
    static Map<String, ConfigScreenFactory<?>> getConfigScreenFactories()
    {
        throw new AssertionError();
    }
}
