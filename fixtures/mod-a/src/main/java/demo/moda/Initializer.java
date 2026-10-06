package demo.moda;

import demo.game.*;
import org.neoforbric.api.ModInitializer;

public final class Initializer implements ModInitializer {
    @Override public void onInitialize() {
        if (ModInitializer.class.getClassLoader() == getClass().getClassLoader()) throw new AssertionError("API should be shared from parent");
        GameState.item = new SharedItem("probe:shared_item");
        GameState.stack = new SharedStack(GameState.item);
        GameState.events.add("A");
    }
}
