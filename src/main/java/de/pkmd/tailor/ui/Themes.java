package de.pkmd.tailor.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import javax.swing.UIManager;

/** FlatLaf look and feel selection. */
public final class Themes {

    private Themes() {
    }

    public static void apply(String name) {
        FlatLaf laf = switch (name == null ? "" : name) {
            case "Dark" -> new FlatDarkLaf();
            case "IntelliJ" -> new FlatIntelliJLaf();
            case "Darcula" -> new FlatDarculaLaf();
            case "macOS Light" -> new FlatMacLightLaf();
            case "macOS Dark" -> new FlatMacDarkLaf();
            default -> new FlatLightLaf();
        };
        boolean installed = UIManager.getLookAndFeel() != null;
        FlatLaf.setup(laf);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("TabbedPane.tabsPopupPolicy", "asNeeded");
        UIManager.put("TabbedPane.scrollButtonsPolicy", "asNeededSingle");
        if (installed) {
            FlatLaf.updateUI();
        }
    }
}
