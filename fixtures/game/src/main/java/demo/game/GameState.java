package demo.game;

import java.util.ArrayList;
import java.util.List;

/** Synthetic shared objects; these are not Minecraft Item / ItemStack or a native registry. */
public final class GameState {
    public static SharedItem item;
    public static SharedStack stack;
    public static final List<String> events = new ArrayList<>();
    private GameState() {}
}
