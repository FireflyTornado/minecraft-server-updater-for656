import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Dev-only: verify each screenshot shows its phase accent chip in the header slot. */
public class ScreenshotProbe {
    public static void main(String[] args) throws Exception {
        String[][] cases = {
            {"01_preparing.png",         "0xFF66CC"},
            {"02_updater_download.png",  "0xFF00FF"},
            {"03_checking.png",          "0x3399FF"},
            {"04_downloading.png",       "0xFF9933"},
            {"05_cleaning.png",          "0x99CC33"},
            {"06_success.png",           "0x33CC66"},
            {"07_partial_failure.png",   "0xE05252"},
            {"08_error.png",             "0xE05252"},
        };
        System.out.println("screenshot              size     accentPx  whiteGlyph  status");
        for (String[] c : cases) {
            File f = new File("..\\screenshots", c[0]);
            BufferedImage img = ImageIO.read(f);
            int target = Integer.decode(c[1]);
            int accent = 0, white = 0;
            // header-left region where the 64px slot sits (incl. its margins)
            for (int y = 12; y <= 96; y++) {
                for (int x = 16; x <= 100; x++) {
                    int rgb = img.getRGB(x, y) & 0xFFFFFF;
                    if (Math.abs((rgb >> 16) - ((target >> 16) & 0xFF)) < 90
                            && Math.abs(((rgb >> 8) & 0xFF) - ((target >> 8) & 0xFF)) < 90
                            && Math.abs((rgb & 0xFF) - (target & 0xFF)) < 90) accent++;
                    if (rgb == 0xFFFFFF) white++;
                }
            }
            boolean ok = accent > 1500 && white > 60;
            System.out.printf("%-26s %dx%-4d %8d  %8d  %s%n",
                    c[0], img.getWidth(), img.getHeight(), accent, white, ok ? "OK" : "MISSING");
        }
    }
}
