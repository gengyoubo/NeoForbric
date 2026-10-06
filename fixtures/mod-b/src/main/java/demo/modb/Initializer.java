package demo.modb;

import demo.game.*;
import org.neoforbric.api.ModInitializer;

public final class Initializer implements ModInitializer {
    @Override public void onInitialize() {
        if (GameState.stack.item != GameState.item) throw new AssertionError("Item identity changed");
        GameState.stack.value = 40;
        GameState.events.add("B");
    }
}
