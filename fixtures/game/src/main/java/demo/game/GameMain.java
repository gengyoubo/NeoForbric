package demo.game;

public final class GameMain {
    public static void main(String[] args) {
        if (!GameState.events.equals(java.util.List.of("A", "B", "C")) || GameState.stack.value != 42
                || GameState.stack.item != GameState.item || GameState.stack.getClass().getClassLoader() != GameMain.class.getClassLoader())
            throw new AssertionError("Shared object / initialization order contract failed");
        System.out.println("FIXTURE_OK mods=A,B,C sameGameLoader=true sharedValue=42");
    }
}
