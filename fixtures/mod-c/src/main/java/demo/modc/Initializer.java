package demo.modc;

import demo.game.*;
import org.neoforbric.api.ModInitializer;

public final class Initializer implements ModInitializer {
    @Override public void onInitialize() {
        if (GameState.stack.value != 40) throw new AssertionError("Earlier write is invisible");
        GameState.stack.value += 2;
        GameState.events.add("C");
    }
}
