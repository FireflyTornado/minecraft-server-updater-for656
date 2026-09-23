package com.zack88604.autoupdater.gui.swing;

import com.zack88604.autoupdater.gui.preset.GuiPreset;
import com.zack88604.autoupdater.gui.preset.GuiPresetSelection;
import com.zack88604.autoupdater.gui.preset.ServerGuiPresetOffer;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Minimal trusted Swing UI used to choose and warn about external GUI presets.
 */
public final class SwingGuiPresetChooser {

    private static final String SWING_LABEL = "Built-in Swing GUI (recommended)";

    private SwingGuiPresetChooser() {
    }

    /**
     * Ask the user to select a GUI preset. Cancellation keeps Swing for this
     * launch and does not save a default.
     */
    public static GuiPresetSelection choose(List<GuiPreset> presets) {
        if (presets == null || presets.isEmpty() || GraphicsEnvironment.isHeadless()) {
            return GuiPresetSelection.swing(false);
        }
        return onEventThread(new Callable<GuiPresetSelection>() {
            @Override
            public GuiPresetSelection call() {
                return showSelectionDialog(presets);
            }
        }, GuiPresetSelection.swing(false));
    }


    /**
     * Ask for explicit trust before a server-published preset runs.
     * The result is persisted by the bootstrap for this exact preset identity.
     */
    public static boolean confirmServerPreset(final ServerGuiPresetOffer offer,
                                              final String serverUrl) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        return onEventThread(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return showServerRiskDialog(offer, serverUrl);
            }
        }, false);
    }

    /** Tell the user that an approved external preset could not be loaded. */
    public static void showLoadFailure(final GuiPreset preset) {
        showMessage("Unable to load external GUI preset \"" + preset.getSelectionLabel()
                        + "\". The built-in Swing GUI will be used instead.",
                "External GUI preset", JOptionPane.ERROR_MESSAGE);
    }

    /** Tell the user that the updater could not read or persist preset settings. */
    public static void showStorageFailure() {
        showMessage("GUI preset settings could not be read or saved. "
                        + "The built-in Swing GUI will be used for this launch.",
                "GUI preset settings", JOptionPane.WARNING_MESSAGE);
    }

    private static GuiPresetSelection showSelectionDialog(List<GuiPreset> presets) {
        JComboBox<String> choices = new JComboBox<String>();
        choices.addItem(SWING_LABEL);
        for (GuiPreset preset : presets) {
            choices.addItem(preset.getSelectionLabel());
        }

        JCheckBox remember = new JCheckBox(
                "Use this selection as the default GUI on future launches", true);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel("Choose the GUI used by the updater:"), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(2, 1, 0, 6));
        center.add(choices);
        center.add(remember);
        panel.add(center, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(null, panel, "Choose updater GUI",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION || choices.getSelectedIndex() == 0) {
            return GuiPresetSelection.swing(result == JOptionPane.OK_OPTION
                    && remember.isSelected());
        }

        GuiPreset preset = presets.get(choices.getSelectedIndex() - 1);
        if (!showRiskDialog(preset)) {
            return GuiPresetSelection.swing(false);
        }
        return GuiPresetSelection.preset(preset, remember.isSelected());
    }

    private static boolean showServerRiskDialog(ServerGuiPresetOffer offer,
                                                 String serverUrl) {
        String message = "The update server offers an external GUI preset:\n\n"
                + offer.getId() + " (" + offer.getVersion() + ")\n"
                + "Server: " + serverUrl + "\n\n"
                + "The downloaded archive matches the descriptor supplied by this update "
                + "server. Loading it still executes external Java code, which may read or "
                + "modify files, access the network, or affect the game process. Only trust "
                + "a server you recognize.\n\n"
                + "Trust this server preset identity and load it?";
        Object[] options = {"Trust and load server GUI", "Use built-in Swing"};
        return options[0].equals(showTextDialog(message, "Server GUI security warning",
                JOptionPane.WARNING_MESSAGE, options, options[1]));
    }

    private static boolean showRiskDialog(GuiPreset preset) {
        String message = "The selected GUI preset is an external Java archive:\n\n"
                + preset.getArchive().getAbsolutePath() + "\n\n"
                + "Loading it executes code supplied by the preset. It may read or modify "
                + "your files, access the network, or affect the game process. Only continue "
                + "if you trust the file and its source.";
        Object[] options = {"Load external GUI", "Use built-in Swing"};
        return options[0].equals(showTextDialog(message, "External GUI security warning",
                JOptionPane.WARNING_MESSAGE, options, options[1]));
    }

    /**
     * Size the text to its wrapped content, while keeping long messages
     * scrollable so the dialog buttons remain visible on smaller screens.
     */
    private static JScrollPane wrapText(String message) {
        JTextArea area = new JTextArea(message);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setFont(UIManager.getFont("Label.font"));

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int width = Math.min(area.getFontMetrics(area.getFont()).charWidth('m') * 60,
                Math.max(200, screen.width - 160));
        area.setSize(new Dimension(width, Integer.MAX_VALUE));
        int contentHeight = area.getPreferredSize().height;

        JScrollPane scroll = new JScrollPane(area,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(width,
                Math.min(contentHeight, Math.max(60, screen.height - 180))));
        return scroll;
    }

    private static Object showTextDialog(String message, String title, int type,
                                         Object[] options, Object initialValue) {
        JScrollPane scroll = wrapText(message);
        JOptionPane pane = new JOptionPane(scroll, type, JOptionPane.DEFAULT_OPTION,
                null, options, initialValue);
        JDialog dialog = pane.createDialog(null, title);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        if (dialog.getHeight() > screen.height) {
            Dimension size = scroll.getPreferredSize();
            size.height = Math.max(1, size.height - (dialog.getHeight() - screen.height));
            scroll.setPreferredSize(size);
            dialog.pack();
        }
        dialog.setLocation(screen.x + (screen.width - dialog.getWidth()) / 2,
                screen.y + (screen.height - dialog.getHeight()) / 2);
        dialog.setVisible(true);
        Object value = pane.getValue();
        dialog.dispose();
        return value;
    }

    private static void showMessage(final String message, final String title, final int type) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        onEventThread(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                showTextDialog(message, title, type, null, null);
                return true;
            }
        }, false);
    }

    private static <T> T onEventThread(Callable<T> action, T fallback) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return action.call();
            } catch (Exception ignored) {
                return fallback;
            }
        }

        final Result<T> result = new Result<T>(fallback);
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        result.value = action.call();
                    } catch (Exception ignored) {
                        // Use the safe fallback value.
                    }
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ignored) {
            // Use the safe fallback value.
        }
        return result.value;
    }

    private static final class Result<T> {
        private T value;

        private Result(T value) {
            this.value = value;
        }
    }
}
