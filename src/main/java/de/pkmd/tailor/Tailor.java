package de.pkmd.tailor;

import de.pkmd.tailor.settings.FileSet;
import de.pkmd.tailor.settings.Settings;
import de.pkmd.tailor.settings.SettingsStore;
import de.pkmd.tailor.ui.MainFrame;
import de.pkmd.tailor.ui.Themes;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Entry point: {@code tailor [--config FILE] [--fileset NAME] [FILE...]}. */
public final class Tailor {

    private static final String USAGE = """
            Usage: tailor [options] [file...]

            Without files and file set, the files of the last session are reopened.

            Options:
              -s, --fileset NAME   open the saved file set NAME
              -c, --config FILE    read and write settings in FILE instead of the user's settings
              -h, --help           show this help
            """;

    private Tailor() {
    }

    public static void main(String[] args) {
        List<Path> files = new ArrayList<>();
        String fileSet = null;
        String config = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> {
                    System.out.print(USAGE);
                    return;
                }
                case "-s", "--fileset" -> fileSet = value(args, ++i, a);
                case "-c", "--config" -> config = value(args, ++i, a);
                default -> {
                    if (a.startsWith("-") && a.length() > 1) {
                        System.err.println("Unknown option " + a + "\n" + USAGE);
                        System.exit(2);
                    }
                    files.add(Path.of(a));
                }
            }
        }
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Tailor");
        SettingsStore store = config != null ? new SettingsStore(Path.of(config)) : SettingsStore.forUser();
        String set = fileSet;
        SwingUtilities.invokeLater(() -> start(store, files, set));
    }

    private static void start(SettingsStore store, List<Path> files, String fileSet) {
        Settings settings = store.load();
        Themes.apply(settings.theme);
        MainFrame frame = new MainFrame(store, settings);
        frame.setVisible(true);
        if (files.isEmpty() && fileSet == null) {
            frame.restoreSession();
            return;
        }
        if (fileSet != null) {
            Optional<FileSet> s = settings.fileSets.stream().filter(f -> f.getName().equals(fileSet)).findFirst();
            if (s.isPresent()) {
                frame.openFileSet(s.get());
            } else {
                JOptionPane.showMessageDialog(frame, "There is no file set named “" + fileSet + "”.",
                        "Tailor", JOptionPane.WARNING_MESSAGE);
            }
        }
        frame.openPaths(files);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            System.err.println(option + " needs a value\n" + USAGE);
            System.exit(2);
        }
        return args[i];
    }
}
