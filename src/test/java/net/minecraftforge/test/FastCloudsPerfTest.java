/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.test;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ProfilerEnableEvent;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(modid = "fastcloudsperftest", name = "Fast Clouds Performance Test", version = "0.0.0", clientSideOnly = true)
public class FastCloudsPerfTest
{
    private static final boolean ENABLED = false;

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        if (ENABLED)
        {
            MinecraftForge.EVENT_BUS.register(this);
        }
    }

    private boolean originalSetting;
    private int timer = 0;
    private static final int TESTS_START = 1;
    private static final int TESTS_END = 5;
    private int tests = 0;
    private int frames = 0;
    private boolean nextTest = false;

    @SubscribeEvent
    public void onProfilingEnable(ProfilerEnableEvent event)
    {
        if (tests >= TESTS_START && tests < TESTS_END)
        {
            event.setEnabled();
        }

        if (nextTest)
        {
            nextTest = false;
            frames = 0;
            Minecraft.getMinecraft().mcProfiler.clearProfiling();

            ForgeModContainer.forgeCloudsEnabled = !ForgeModContainer.forgeCloudsEnabled;
            timer = 200;
            tests++;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event)
    {
        if (event.phase == TickEvent.Phase.END && Minecraft.getMinecraft().world != null && !nextTest)
        {
            switch (event.type)
            {
            case RENDER:
                if (tests < TESTS_END)
                    frames++;
                break;
            case CLIENT:
                if (--timer < 0 && tests < TESTS_END)
                {
                    // Let the renderer settle first
                    if (tests >= TESTS_START && frames > 0)
                    {
                        long time = Minecraft.getMinecraft().mcProfiler.getTotalTime("root.gameRenderer.level.clouds");

                        if (time > 0)
                        {
                            FMLLog.info(
                                    (ForgeModContainer.forgeCloudsEnabled ? "Forge clouds took " : "Vanilla clouds took ")
                                    + (time / 1e+6F / frames)
                                    + "ms per frame average");
                        }
                    }
                    else if (tests == 0)
                    {   // Save the original setting to revert after we're finished.
                        originalSetting = ForgeModContainer.forgeCloudsEnabled;
                    }

                    // Reset our profiling for the inverted test
                    nextTest = true;
                }
                else if (tests == TESTS_END)
                {
                    ForgeModContainer.forgeCloudsEnabled = originalSetting;
                    FMLLog.info("Switched back to %s",
                            ForgeModContainer.forgeCloudsEnabled ? "Forge clouds" : "vanilla clouds");
                    tests++;
                }
                break;
            default:
                break;
            }
        }
    }
}
