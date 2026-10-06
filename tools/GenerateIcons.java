import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Generates only NeoForbric's own woven cube. Third-party logos are vendored upstream assets. */
public class GenerateIcons {
    static final String[] CUBE = {
        "................", ".......oo.......", ".....ooaaoo.....", "...ooaaaabbooo..",
        "..oaaaabbbbcco..", "..oaaabbbbccco..", "..oabbbbccccco..", "..oobbccccccoo..",
        "..oddobcccoeeo..", "..odddocooeeeo..", "..oddddooeeeeo..", "..oddddoeeeeeo..",
        "...odddoeeeoo...", ".....odoeoo.....", ".......oo.......", "................"
    };
    static int color(char pixel) {
        return switch (pixel) {
            case 'o' -> 0xff203d43; case 'a' -> 0xffe9d4a9; case 'b' -> 0xffa0e6d2;
            case 'c' -> 0xff5ecab8; case 'd' -> 0xff348b91; case 'e' -> 0xff286269;
            default -> 0;
        };
    }
    public static void main(String[] args) throws Exception {
        Path icons = Path.of(args[0]).resolve("client-ui/src/main/resources/assets/neoforbric/icons");
        Files.createDirectories(icons);
        for (int size : new int[]{16,32,128}) {
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                String row = CUBE[y * 16 / size]; int column = x * 16 / size;
                image.setRGB(x, y, column < row.length() ? color(row.charAt(column)) : 0);
            }
            ImageIO.write(image, "PNG", icons.resolve("neoforbric-" + size + ".png").toFile());
        }
    }
}
