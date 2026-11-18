package com.vgeri108.loadingmusic;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.ImmediateWindowHandler;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ServiceLoader;
import java.util.concurrent.ScheduledExecutorService;

public final class LoadingMusicBootstrapper implements GraphicsBootstrapper {

    private static final Logger LOGGER = LogManager.getLogger("LoadingMusic");
    private static final String STARGATE_WINDOW_CLASS =
            "cz.lukaskabc.minecraft.mod_loader.loading.stargate_early_loading.StargateEarlyLoadingWindow";
    private static final String STARGATE_PROVIDER_NAME = "StargateEarlyLoading";
    private static final VarHandle ACTIVE_PROVIDER;
    private static final VarHandle RENDER_SCHEDULER;

    static {
        try {
            ACTIVE_PROVIDER = MethodHandles.privateLookupIn(
                            ImmediateWindowHandler.class,
                            MethodHandles.lookup()
                    )
                    .findStaticVarHandle(
                            ImmediateWindowHandler.class,
                            "provider",
                            ImmediateWindowProvider.class
                    );
            RENDER_SCHEDULER = MethodHandles.privateLookupIn(
                            DisplayWindow.class,
                            MethodHandles.lookup()
                    )
                    .findVarHandle(
                            DisplayWindow.class,
                            "renderScheduler",
                            ScheduledExecutorService.class
                    );
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public String name() {
        return "LoadingMusic";
    }

    @Override
    public void bootstrap(String[] arguments) {
        // This SPI runs before NeoForge reads the early-window provider setting.
        if (stargateProviderAvailable()) {
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL, true);
            FMLConfig.updateConfig(
                    FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER,
                    STARGATE_PROVIDER_NAME
            );
        }

        Thread monitor = new Thread(LoadingMusicBootstrapper::monitorWindow, "LoadingMusic-Lifecycle");
        monitor.setDaemon(true);
        monitor.start();
    }

    private static boolean stargateProviderAvailable() {
        ModuleLayer serviceLayer = Launcher.INSTANCE.findLayerManager()
                .flatMap(manager -> manager.getLayer(IModuleLayerManager.Layer.SERVICE))
                .orElseThrow(() -> new IllegalStateException("NeoForge service layer is unavailable"));

        return ServiceLoader.load(serviceLayer, ImmediateWindowProvider.class)
                .stream()
                .map(ServiceLoader.Provider::type)
                .anyMatch(provider -> STARGATE_WINDOW_CLASS.equals(provider.getName()));
    }

    private static void monitorWindow() {
        try {
            ImmediateWindowProvider provider;
            do {
                Thread.sleep(25);
                provider = (ImmediateWindowProvider) ACTIVE_PROVIDER.getVolatile();
            } while (provider == null);

            if (!STARGATE_WINDOW_CLASS.equals(provider.getClass().getName())) {
                return;
            }

            ScheduledExecutorService scheduler;
            do {
                Thread.sleep(25);
                scheduler = (ScheduledExecutorService) RENDER_SCHEDULER.get(provider);
            } while (scheduler == null);

            if (scheduler.isShutdown()) {
                return;
            }

            LOGGER.info("Starting early loading music");
            OpenALAudioPlayer.start();

            // DisplayWindow shuts this scheduler down when the early window closes.
            while (!scheduler.isShutdown()) {
                Thread.sleep(25);
            }

            LOGGER.info("Stopping early loading music");
            OpenALAudioPlayer.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            OpenALAudioPlayer.stop();
        }
    }
}
