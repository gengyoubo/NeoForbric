package demo.clientprobe;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import java.nio.file.*;

/** Plain Fabric client SPI and vanilla/LWJGL calls, with no NeoForbric API dependency. */
public final class ClientProbe implements ClientModInitializer {
    public static int initialized;
    @Override public void onInitializeClient() {
        if (++initialized != 1 || BuiltInRegistries.ITEM.get(ClientItemProbe.ID) != ClientItemProbe.ITEM) throw new AssertionError("Client entrypoint item identity differs");
    }
    public static void verifyClient(Object instance) throws Exception {
        Minecraft client = (Minecraft) instance;
        if (Boolean.getBoolean("neoforbric.probe.client.fail")) throw new IllegalStateException("intentional client frame failure");
        if (initialized != 1 || ClientItemProbe.initialized != 1 || !(client.screen instanceof TitleScreen) || client.getOverlay() != null
                || client.getWindow().getWindow() == 0 || !GL.getCapabilities().OpenGL32 || new ItemStack(ClientItemProbe.ITEM).getItem() != ClientItemProbe.ITEM
                || Minecraft.class.getClassLoader() != ClientProbe.class.getClassLoader()) throw new AssertionError("Client menu / OpenGL / item / classloader differs");
        Path screenshot = client.gameDirectory.toPath().resolve("screenshots/neoforbric-main-menu.png"); Files.createDirectories(screenshot.getParent());
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(screenshot); }
        net.minecraft.server.Bootstrap.realStdoutPrintln("CLIENT_PROBE_OK main=1 client=1 menu=TitleScreen resourcesLoaded=true itemIdentity=true gameLoader=NeoForbric-Game renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        if (Boolean.getBoolean("neoforbric.probe.mods")) client.getClass().getClassLoader().loadClass("org.neoforbric.client.NeoForbricClientUi").getMethod("verifyTitleScreen", Object.class).invoke(null, client);
    }
}
