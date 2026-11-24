package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.HighlightProfile;
import de.pkmd.tailor.highlight.HighlightRule;
import de.pkmd.tailor.highlight.Query;
import de.pkmd.tailor.highlight.Highlighter;
import de.pkmd.tailor.io.FilePoller;
import de.pkmd.tailor.io.LogFile;
import de.pkmd.tailor.settings.FileEntry;
import de.pkmd.tailor.settings.FileSet;
import de.pkmd.tailor.settings.Settings;
import de.pkmd.tailor.settings.SettingsStore;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Main window: tabs of log files, menus, toolbar and status bar. */
public final class MainFrame extends JFrame {

    private static final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    private static final List<String> COMMON_CHARSETS = List.of("UTF-8", "ISO-8859-1", "ISO-8859-15",
            "windows-1252", "UTF-16LE", "UTF-16BE", "US-ASCII");

    private final SettingsStore store;
    private final Settings settings;
    private final FilePoller poller;
    private final ExecutorService background = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "search");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Highlighter> highlighters = new HashMap<>();
    private ViewConfig viewConfig;

    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel statusLeft = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private final JToggleButton followButton = new JToggleButton("Follow");
    private final JToggleButton wrapButton = new JToggleButton("Wrap");
    private final JComboBox<String> profileCombo = new JComboBox<>();
    private final JCheckBoxMenuItem followItem = new JCheckBoxMenuItem();
    private final JCheckBoxMenuItem wrapItem = new JCheckBoxMenuItem();
    private final JCheckBoxMenuItem lineNumbersItem = new JCheckBoxMenuItem();
    private final Timer statusTimer = new Timer(1000, e -> updateStatus());
    private final Timer autosaveTimer = new Timer(30_000, e -> autosave());
    private final JToggleButton filterButton = new JToggleButton("Filter");
    private final JCheckBoxMenuItem filterItem = new JCheckBoxMenuItem();
    private final FilterPane filterPane;
    private final FindBar findBar;
    /** Tabs with the find bar below; the top of the split when the filter pane is shown. */
    private final JPanel mainArea = new JPanel(new BorderLayout());
    private final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private boolean filterVisible;
    private String lastSavedJson;
    private boolean syncing;

    public MainFrame(SettingsStore store, Settings settings) {
        super("Tailor");
        this.store = store;
        this.settings = settings;
        this.poller = new FilePoller(settings.pollIntervalMs);
        this.viewConfig = ViewConfig.from(settings);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImages(Icons.appIcons());

        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close (" + keyText(KeyEvent.VK_W, MENU) + ")");
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", (IntConsumer) i -> closeTab(tab(i)));
        tabs.putClientProperty("JTabbedPane.showTabSeparators", true);
        tabs.addChangeListener(e -> selectedTabChanged());
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleTabMouse(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleTabMouse(e);
            }
        });

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        status.add(statusLeft, BorderLayout.CENTER);
        status.add(statusRight, BorderLayout.EAST);

        filterPane = new FilterPane(this);
        filterPane.restore(settings.filter);
        findBar = new FindBar(this);
        findBar.restore(settings.find);
        findBar.setVisible(false);
        mainArea.add(tabs, BorderLayout.CENTER);
        mainArea.add(findBar, BorderLayout.SOUTH);
        split.setResizeWeight(0.7);
        split.setContinuousLayout(true);
        split.setBorder(null);

        setJMenuBar(createMenuBar());
        getContentPane().add(createToolBar(), BorderLayout.NORTH);
        getContentPane().add(mainArea, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);
        FileDropHandler drop = new FileDropHandler();
        setTransferHandler(drop);
        tabs.setTransferHandler(drop);
        applyTabPlacement();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });
        restoreBounds();
        statusTimer.start();
        autosaveTimer.start();
        if (settings.filter.visible) {
            setFilterVisible(true);
        }
        updateControls();
    }

    // ------------------------------------------------------------------ accessors for tabs

    FilePoller poller() {
        return poller;
    }

    ExecutorService background() {
        return background;
    }

    Settings settings() {
        return settings;
    }

    private LogTab tab(int i) {
        return i >= 0 && i < tabs.getTabCount() ? (LogTab) tabs.getComponentAt(i) : null;
    }

    private LogTab current() {
        return tab(tabs.getSelectedIndex());
    }

    boolean isSelected(LogTab t) {
        return t == current();
    }

    private List<LogTab> allTabs() {
        List<LogTab> l = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            l.add(tab(i));
        }
        return l;
    }

    private void withTab(Consumer<LogTab> action) {
        LogTab t = current();
        if (t != null) {
            action.accept(t);
        }
    }

    Highlighter highlighter(String profile) {
        HighlightProfile p = settings.profile(profile);
        return highlighters.computeIfAbsent(p.getName(), n -> Highlighter.compile(p));
    }

    // ------------------------------------------------------------------ opening and closing

    public void openPaths(List<Path> paths) {
        LogTab first = null;
        for (Path p : paths) {
            LogTab t = openFile(new FileEntry(p.toString()));
            if (first == null) {
                first = t;
            }
        }
        if (first != null) {
            tabs.setSelectedComponent(first);
        }
    }

    /** Opens the file (or selects its tab if it is already open). */
    LogTab openFile(FileEntry entry) {
        Path path = Path.of(entry.getPath()).toAbsolutePath().normalize();
        for (LogTab t : allTabs()) {
            if (t.file().path().equals(path)) {
                return t;
            }
        }
        LogFile file = new LogFile(path);
        file.setDefaultCharset(charsetOrNull(settings.defaultCharset, Charset.defaultCharset()));
        file.setMaxLineBytes(settings.maxLineKiB * 1024);
        Charset cs = charsetOrNull(entry.getCharset(), null);
        if (cs != null) {
            file.setCharset(cs);
        }
        String profile = settings.findProfile(entry.getProfile()).map(HighlightProfile::getName)
                .orElse(HighlightProfile.DEFAULT_NAME);
        LogTab tab = new LogTab(this, file, viewConfig, highlighter(profile), profile, entry.getState());
        tab.view().setSearchPattern(findBar.highlightPattern());
        tabs.addTab(path.getFileName().toString(), null, tab, path.toString());
        settings.addRecent(path.toString());
        poller.add(file);
        if (tabs.getTabCount() == 1) {
            tabs.setSelectedIndex(0);
        }
        return tab;
    }

    public void openFileSet(FileSet set) {
        LogTab first = null;
        List<String> missing = new ArrayList<>();
        for (FileEntry e : set.getFiles()) {
            List<Path> paths = e.resolve();
            if (paths.isEmpty()) {
                missing.add(e.getPath());
            }
            for (Path p : paths) {
                FileEntry concrete = e.copy();
                concrete.setPath(p.toString());
                LogTab t = openFile(concrete);
                if (first == null) {
                    first = t;
                }
            }
        }
        if (first != null) {
            tabs.setSelectedComponent(first);
        }
        if (!missing.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files match:\n" + String.join("\n", missing),
                    "File set “" + set.getName() + "”", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void closeTab(LogTab t) {
        if (t != null) {
            tabs.remove(t);
            t.dispose();
        }
    }

    private void closeOthers(LogTab keep) {
        for (LogTab t : allTabs()) {
            if (t != keep) {
                closeTab(t);
            }
        }
    }

    private void openDialog() {
        JFileChooser fc = new JFileChooser(settings.lastDirectory);
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Open Log Files");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] files = fc.getSelectedFiles();
            if (files.length > 0) {
                settings.lastDirectory = files[0].getParent();
                openPaths(java.util.Arrays.stream(files).map(File::toPath).toList());
            }
        }
    }

    public void restoreSession() {
        if (!settings.restoreSession || settings.session.isEmpty()) {
            return;
        }
        for (FileEntry e : settings.session) {
            openFile(e);
        }
        if (settings.sessionSelected >= 0 && settings.sessionSelected < tabs.getTabCount()) {
            tabs.setSelectedIndex(settings.sessionSelected);
        }
    }

    /** Copies open tabs and window geometry into the settings. */
    private void captureSession() {
        settings.session = openTabEntries(true);
        filterPane.store(settings.filter);
        findBar.store(settings.find);
        settings.filter.visible = filterVisible;
        if (filterVisible) {
            settings.filter.divider = split.getDividerLocation();
        }
        settings.sessionSelected = tabs.getSelectedIndex();
        settings.windowMaximized = (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        if (!settings.windowMaximized && isShowing()) {
            settings.windowX = getX();
            settings.windowY = getY();
            settings.windowWidth = getWidth();
            settings.windowHeight = getHeight();
        }
    }

    /** Saves the session now and then, so a crash does not lose it. */
    private void autosave() {
        captureSession();
        String json = SettingsStore.toJson(settings);
        if (!json.equals(lastSavedJson)) {
            saveSettings();
        }
    }

    private void exit() {
        captureSession();
        saveSettings();
        for (LogTab t : allTabs()) {
            t.dispose();
        }
        poller.close();
        background.shutdownNow();
        dispose();
        System.exit(0);
    }

    /**
     * Entries for the open tabs.
     *
     * @param withState with find query, position, bookmarks etc. (session); else without (file sets)
     */
    List<FileEntry> openTabEntries(boolean withState) {
        List<FileEntry> l = new ArrayList<>();
        for (LogTab t : allTabs()) {
            Charset cs = t.file().forcedCharset();
            String profile = HighlightProfile.DEFAULT_NAME.equals(t.profileName()) ? null : t.profileName();
            FileEntry e = new FileEntry(t.file().path().toString(), profile, cs == null ? null : cs.name());
            if (withState) {
                e.setState(t.captureState());
            }
            l.add(e);
        }
        return l;
    }

    void saveSettings() {
        try {
            store.save(settings);
            lastSavedJson = SettingsStore.toJson(settings);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Cannot save settings to " + store.file() + ":\n" + e.getMessage(),
                    "Tailor", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restoreBounds() {
        setSize(Math.max(400, settings.windowWidth), Math.max(300, settings.windowHeight));
        if (settings.windowX >= 0 && settings.windowY >= 0
                && isOnScreen(settings.windowX, settings.windowY)) {
            setLocation(settings.windowX, settings.windowY);
        } else {
            setLocationRelativeTo(null);
        }
        if (settings.windowMaximized) {
            setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }

    private static boolean isOnScreen(int x, int y) {
        for (var gd : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (gd.getDefaultConfiguration().getBounds().contains(x + 50, y + 20)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ tab callbacks

    void tabUpdated(LogTab t, boolean grew) {
        int i = tabs.indexOfComponent(t);
        if (i < 0) {
            return;
        }
        if (grew && filterVisible) {
            filterPane.tabGrew(t);
        }
        updateTabTitle(i);
        if (t == current()) {
            updateStatus();
        }
    }

    void tabStatusChanged(LogTab t) {
        if (t == current()) {
            updateStatus();
            updateControls();
        }
    }

    private void updateTabTitle(int i) {
        LogTab t = tab(i);
        String name = t.file().path().getFileName().toString();
        boolean missing = t.file().state() == LogFile.State.MISSING;
        String title = (t.isUnseen() ? "● " : "") + name + (missing ? " (missing)" : "");
        if (!title.equals(tabs.getTitleAt(i))) {
            tabs.setTitleAt(i, title);
        }
    }

    private void selectedTabChanged() {
        LogTab t = current();
        if (filterVisible) {
            filterPane.show(t);
        }
        if (t != null) {
            t.markSeen();
            updateTabTitle(tabs.getSelectedIndex());
            SwingUtilities.invokeLater(() -> t.view().requestFocusInWindow());
            setTitle(t.file().path() + " – Tailor");
        } else {
            setTitle("Tailor");
        }
        updateControls();
        updateStatus();
    }

    private void updateStatus() {
        LogTab t = current();
        if (t == null) {
            statusLeft.setText("Open a file (" + keyText(KeyEvent.VK_O, MENU)
                    + "), drop files here, or open a file set.");
            statusRight.setText(" ");
            return;
        }
        String msg = t.message();
        if (msg == null) {
            LogFile f = t.file();
            msg = f.state() == LogFile.State.ERROR ? "Error: " + f.error() : f.path().toString();
            if (f.lastAppend() > 0) {
                long ago = (System.currentTimeMillis() - f.lastAppend()) / 1000;
                msg += "   • updated " + (ago < 2 ? "just now" : ago < 120 ? ago + " s ago" : ago / 60 + " min ago");
            }
        }
        statusLeft.setText(msg);
        statusRight.setText(t.statusText() + (t.view().isFollow() ? "   ⬇ Following" : ""));
    }

    private void updateControls() {
        syncing = true;
        try {
            LogTab t = current();
            boolean follow = t != null && t.view().isFollow();
            followButton.setSelected(follow);
            followItem.setSelected(follow);
            filterButton.setSelected(filterVisible);
            filterItem.setSelected(filterVisible);
            wrapButton.setSelected(settings.wrap);
            wrapItem.setSelected(settings.wrap);
            lineNumbersItem.setSelected(settings.showLineNumbers);
            profileCombo.removeAllItems();
            for (HighlightProfile p : settings.profiles) {
                profileCombo.addItem(p.getName());
            }
            profileCombo.setSelectedItem(t != null ? t.profileName() : null);
            profileCombo.setEnabled(t != null);
        } finally {
            syncing = false;
        }
    }

    private void handleTabMouse(MouseEvent e) {
        int i = tabs.indexAtLocation(e.getX(), e.getY());
        if (i < 0) {
            return;
        }
        LogTab t = tab(i);
        if (e.getID() == MouseEvent.MOUSE_RELEASED && SwingUtilities.isMiddleMouseButton(e)) {
            closeTab(t);
        } else if (e.isPopupTrigger()) {
            JPopupMenu m = new JPopupMenu();
            m.add(item("Close", () -> closeTab(t)));
            m.add(item("Close Others", () -> closeOthers(t)));
            m.add(item("Close All", () -> closeOthers(null)));
            m.addSeparator();
            m.add(item("Copy Path", () -> copyToClipboard(t.file().path().toString())));
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                m.add(item("Open Containing Folder", () -> {
                    try {
                        Desktop.getDesktop().open(t.file().path().getParent().toFile());
                    } catch (IOException | RuntimeException ex) {
                        JOptionPane.showMessageDialog(this, ex.getMessage(), "Tailor", JOptionPane.ERROR_MESSAGE);
                    }
                }));
            }
            m.add(item("Reload", t::reload));
            m.show(tabs, e.getX(), e.getY());
        }
    }

    // ------------------------------------------------------------------ settings

    void zoom(int delta) {
        int size = delta == 0 ? 13 : settings.fontSize + delta;
        settings.fontSize = Math.max(6, Math.min(72, size));
        applySettings();
    }

    /** Re-applies all settings to the open tabs, e.g. after the settings dialog. */
    void applySettings() {
        settings.normalize();
        applyTabPlacement();
        poller.setInterval(settings.pollIntervalMs);
        viewConfig = ViewConfig.from(settings);
        highlighters.clear();
        for (LogTab t : allTabs()) {
            t.file().setMaxLineBytes(settings.maxLineKiB * 1024);
            t.file().setDefaultCharset(charsetOrNull(settings.defaultCharset, Charset.defaultCharset()));
            String profile = settings.findProfile(t.profileName()).map(HighlightProfile::getName)
                    .orElse(HighlightProfile.DEFAULT_NAME);
            t.setProfile(profile, highlighter(profile));
            t.setConfig(viewConfig);
        }
        updateControls();
        updateStatus();
    }

    void applyTheme() {
        Themes.apply(settings.theme);
        applySettings();
    }

    // ------------------------------------------------------------------ find bar

    private void showFind(String initial) {
        findBar.activate(initial);
        mainArea.revalidate();
        findPatternChanged();
    }

    /** Next or previous match of the find query in the selected tab. */
    void findNext(boolean forward) {
        LogTab t = current();
        QueryBar qb = findBar.queryBar();
        Query q = qb.query();
        if (q == null) {
            if (!findBar.isVisible()) {
                showFind(null);
            }
            return;
        }
        if (t != null) {
            qb.remember();
            t.find(q, qb.text(), forward, qb::setFound);
        }
    }

    void closeFind() {
        findBar.setVisible(false);
        mainArea.revalidate();
        findPatternChanged();
        for (LogTab t : allTabs()) {
            t.clearSearch();
        }
        withTab(t -> t.view().requestFocusInWindow());
    }

    /** The find query or its options changed: mark matches in every tab. */
    void findPatternChanged() {
        java.util.regex.Pattern p = findBar.highlightPattern();
        for (LogTab t : allTabs()) {
            t.view().setSearchPattern(p);
        }
    }

    // ------------------------------------------------------------------ filter pane

    /** Shows or hides the filter pane below the tabs; its query stays the same for all tabs. */
    void setFilterVisible(boolean visible) {
        if (visible == filterVisible) {
            return;
        }
        filterVisible = visible;
        java.awt.Container c = getContentPane();
        if (visible) {
            c.remove(mainArea);
            split.setTopComponent(mainArea);
            split.setBottomComponent(filterPane);
            c.add(split, BorderLayout.CENTER);
            filterPane.show(current());
            int divider = settings.filter.divider;
            c.revalidate();
            SwingUtilities.invokeLater(() -> {
                if (divider > 80 && divider < split.getHeight() - 60) {
                    split.setDividerLocation(divider);
                } else {
                    split.setDividerLocation(0.65);
                }
            });
        } else {
            settings.filter.divider = split.getDividerLocation();
            filterPane.show(null);
            c.remove(split);
            split.setTopComponent(null);
            split.setBottomComponent(null);
            c.add(mainArea, BorderLayout.CENTER);
            c.revalidate();
            withTab(t -> t.view().requestFocusInWindow());
        }
        c.repaint();
        updateControls();
    }

    /** Opens the filter pane and focuses its query; hides it if the query already has the focus. */
    private void toggleFilter() {
        java.awt.Component focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (!filterVisible) {
            setFilterVisible(true);
            filterPane.focusQuery();
        } else if (filterPane.hasFocus(focus)) {
            setFilterVisible(false);
        } else {
            filterPane.focusQuery();
        }
    }

    private void applyTabPlacement() {
        tabs.setTabPlacement(switch (settings.tabPlacement) {
            case "Bottom" -> JTabbedPane.BOTTOM;
            case "Left" -> JTabbedPane.LEFT;
            case "Right" -> JTabbedPane.RIGHT;
            default -> JTabbedPane.TOP;
        });
    }

    private void setWrap(boolean wrap) {
        settings.wrap = wrap;
        viewConfig = viewConfig.withWrap(wrap);
        for (LogTab t : allTabs()) {
            t.setConfig(viewConfig);
        }
        updateControls();
    }

    private void setLineNumbers(boolean on) {
        settings.showLineNumbers = on;
        viewConfig = viewConfig.withLineNumbers(on);
        for (LogTab t : allTabs()) {
            t.setConfig(viewConfig);
        }
        updateControls();
    }

    private void setFollow(boolean follow) {
        withTab(t -> {
            t.view().setFollow(follow);
            if (follow) {
                t.view().goToEnd();
            }
        });
        updateControls();
    }

    private void editHighlights(HighlightRule newRule) {
        LogTab t = current();
        String profile = t != null ? t.profileName() : HighlightProfile.DEFAULT_NAME;
        HighlightEditor.show(this, settings, profile, newRule, sampleLines(t), () -> {
            highlighters.clear();
            applySettings();
            saveSettings();
        });
    }

    private static List<String> sampleLines(LogTab t) {
        List<String> lines = new ArrayList<>();
        if (t != null) {
            LineSource src = t.view().source();
            long start = Math.max(0, t.view().topLine());
            for (long l = start; l < Math.min(src.lineCount(), start + 30); l++) {
                lines.add(src.line(l));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ menus

    private JMenuBar createMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(item("Open…", KeyStroke.getKeyStroke(KeyEvent.VK_O, MENU), this::openDialog));
        JMenu recent = new JMenu("Open Recent");
        onShow(recent, () -> {
            recent.removeAll();
            for (String p : settings.recentFiles) {
                recent.add(item(p, () -> openPaths(List.of(Path.of(p)))));
            }
            if (settings.recentFiles.isEmpty()) {
                JMenuItem none = new JMenuItem("(none)");
                none.setEnabled(false);
                recent.add(none);
            } else {
                recent.addSeparator();
                recent.add(item("Clear List", () -> settings.recentFiles.clear()));
            }
        });
        file.add(recent);
        file.addSeparator();
        file.add(item("Reload", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), () -> withTab(LogTab::reload)));
        file.add(item("Close Tab", KeyStroke.getKeyStroke(KeyEvent.VK_W, MENU), () -> closeTab(current())));
        file.add(item("Close All Tabs", KeyStroke.getKeyStroke(KeyEvent.VK_W, MENU | InputEvent.SHIFT_DOWN_MASK),
                () -> closeOthers(null)));
        file.addSeparator();
        file.add(item("Export Settings…", this::exportSettings));
        file.add(item("Import Settings…", this::importSettings));
        file.addSeparator();
        file.add(item("Exit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, MENU), this::exit));
        mb.add(file);

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');
        edit.add(item("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, MENU), this::copySelection));
        edit.add(item("Select All", KeyStroke.getKeyStroke(KeyEvent.VK_A, MENU), () -> {
            LogView v = focusedView();
            if (v != null) {
                v.selectAll();
            }
        }));
        edit.addSeparator();
        edit.add(item("Find…", KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU), () -> {
            LogView v = focusedView();
            LogView.Pos s = v == null ? null : v.selectionStart();
            boolean oneLine = s != null && s.line() == v.selectionEnd().line();
            showFind(oneLine ? v.selectedText() : null);
        }));
        edit.add(item("Find Next", KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), () -> findNext(true)));
        edit.add(item("Find Previous", KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK),
                () -> findNext(false)));
        edit.addSeparator();
        edit.add(item("Settings…", KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, MENU),
                () -> SettingsDialog.show(this, settings)));
        mb.add(edit);

        JMenu view = new JMenu("View");
        view.setMnemonic('V');
        followItem.setAction(action("Follow Tail", KeyStroke.getKeyStroke(KeyEvent.VK_T, MENU),
                () -> setFollow(followItem.isSelected())));
        view.add(followItem);
        wrapItem.setAction(action("Word Wrap", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.ALT_DOWN_MASK),
                () -> setWrap(wrapItem.isSelected())));
        view.add(wrapItem);
        lineNumbersItem.setAction(action("Line Numbers", KeyStroke.getKeyStroke(KeyEvent.VK_L,
                MENU | InputEvent.SHIFT_DOWN_MASK), () -> setLineNumbers(lineNumbersItem.isSelected())));
        view.add(lineNumbersItem);
        filterItem.setAction(action("Filter Pane", KeyStroke.getKeyStroke(KeyEvent.VK_F,
                MENU | InputEvent.SHIFT_DOWN_MASK), this::toggleFilter));
        view.add(filterItem);
        view.addSeparator();
        view.add(item("Zoom In", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, MENU), () -> zoom(1)));
        view.add(item("Zoom Out", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, MENU), () -> zoom(-1)));
        view.add(item("Reset Zoom", KeyStroke.getKeyStroke(KeyEvent.VK_0, MENU), () -> zoom(0)));
        view.addSeparator();
        JMenu encoding = new JMenu("Encoding");
        onShow(encoding, () -> buildEncodingMenu(encoding));
        view.add(encoding);
        JMenu theme = new JMenu("Theme");
        onShow(theme, () -> {
            theme.removeAll();
            ButtonGroup g = new ButtonGroup();
            for (String name : Settings.THEMES) {
                JRadioButtonMenuItem it = new JRadioButtonMenuItem(name, name.equals(settings.theme));
                it.addActionListener(e -> {
                    settings.theme = name;
                    applyTheme();
                });
                g.add(it);
                theme.add(it);
            }
        });
        view.add(theme);
        mb.add(view);

        JMenu nav = new JMenu("Navigate");
        nav.setMnemonic('N');
        nav.add(item("Go to Line…", KeyStroke.getKeyStroke(KeyEvent.VK_G, MENU), this::goToLineDialog));
        nav.add(item("Go to Start", KeyStroke.getKeyStroke(KeyEvent.VK_HOME, MENU),
                () -> withTab(t -> t.view().goToStart())));
        nav.add(item("Go to End", KeyStroke.getKeyStroke(KeyEvent.VK_END, MENU),
                () -> withTab(t -> t.view().goToEnd())));
        nav.addSeparator();
        nav.add(item("Next Highlighted Line", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK),
                () -> withTab(t -> t.nextHighlighted(true))));
        nav.add(item("Previous Highlighted Line", KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK),
                () -> withTab(t -> t.nextHighlighted(false))));
        nav.addSeparator();
        nav.add(item("Toggle Bookmark", KeyStroke.getKeyStroke(KeyEvent.VK_F2, MENU),
                () -> withTab(t -> t.view().toggleBookmark())));
        nav.add(item("Next Bookmark", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0),
                () -> withTab(t -> t.view().nextBookmark(true))));
        nav.add(item("Previous Bookmark", KeyStroke.getKeyStroke(KeyEvent.VK_F2, InputEvent.SHIFT_DOWN_MASK),
                () -> withTab(t -> t.view().nextBookmark(false))));
        nav.add(item("Clear Bookmarks", () -> withTab(t -> {
            t.view().bookmarks().clear();
            t.view().repaint();
        })));
        nav.addSeparator();
        nav.add(item("Next Tab", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, MENU), () -> cycleTab(1)));
        nav.add(item("Previous Tab", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, MENU), () -> cycleTab(-1)));
        mb.add(nav);

        JMenu hl = new JMenu("Highlighting");
        hl.setMnemonic('H');
        onShow(hl, () -> {
            hl.removeAll();
            hl.add(item("Edit Highlight Rules…", KeyStroke.getKeyStroke(KeyEvent.VK_H,
                    MENU | InputEvent.SHIFT_DOWN_MASK), () -> editHighlights(null)));
            hl.add(item("New Rule from Selection…", this::ruleFromSelection));
            hl.addSeparator();
            JMenuItem label = new JMenuItem("Profile for this tab:");
            label.setEnabled(false);
            hl.add(label);
            ButtonGroup g = new ButtonGroup();
            LogTab t = current();
            for (HighlightProfile p : settings.profiles) {
                JRadioButtonMenuItem it = new JRadioButtonMenuItem(p.getName(),
                        t != null && p.getName().equals(t.profileName()));
                it.setEnabled(t != null);
                it.addActionListener(e -> setTabProfile(p.getName()));
                g.add(it);
                hl.add(it);
            }
        });
        mb.add(hl);

        JMenu sets = new JMenu("File Sets");
        sets.setMnemonic('S');
        onShow(sets, () -> {
            sets.removeAll();
            for (FileSet s : settings.fileSets) {
                sets.add(item(s.getName() + "  (" + s.getFiles().size() + ")", () -> openFileSet(s)));
            }
            if (settings.fileSets.isEmpty()) {
                JMenuItem none = new JMenuItem("(no file sets yet)");
                none.setEnabled(false);
                sets.add(none);
            }
            sets.addSeparator();
            sets.add(item("Save Open Tabs as File Set…", this::saveTabsAsFileSet));
            sets.add(item("Manage File Sets…", () -> FileSetDialog.show(this, settings)));
        });
        mb.add(sets);

        JMenu help = new JMenu("Help");
        help.add(item("Keyboard Shortcuts", this::showShortcuts));
        help.add(item("About Tailor", () -> JOptionPane.showMessageDialog(this,
                "Tailor – a live log viewer\n\nJava " + System.getProperty("java.version")
                        + "\nSettings: " + store.file(), "About", JOptionPane.INFORMATION_MESSAGE)));
        mb.add(help);
        return mb;
    }

    private JToolBar createToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JButton open = new JButton("Open…");
        open.addActionListener(e -> openDialog());
        tb.add(open);
        JButton setsButton = new JButton("File Sets ▾");
        setsButton.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            for (FileSet s : settings.fileSets) {
                m.add(item(s.getName(), () -> openFileSet(s)));
            }
            if (!settings.fileSets.isEmpty()) {
                m.addSeparator();
            }
            m.add(item("Save Open Tabs as File Set…", this::saveTabsAsFileSet));
            m.add(item("Manage File Sets…", () -> FileSetDialog.show(this, settings)));
            m.show(setsButton, 0, setsButton.getHeight());
        });
        tb.add(setsButton);
        tb.addSeparator();
        followButton.setToolTipText("Follow the end of the file (" + keyText(KeyEvent.VK_T, MENU) + ")");
        followButton.addActionListener(e -> {
            if (!syncing) {
                setFollow(followButton.isSelected());
            }
        });
        tb.add(followButton);
        wrapButton.setToolTipText("Wrap long lines (Alt+Z)");
        wrapButton.addActionListener(e -> {
            if (!syncing) {
                setWrap(wrapButton.isSelected());
            }
        });
        tb.add(wrapButton);
        tb.addSeparator();
        JButton find = new JButton("Find…");
        find.addActionListener(e -> showFind(null));
        tb.add(find);
        filterButton.setToolTipText("Show only matching lines in a lower pane ("
                + keyText(KeyEvent.VK_F, MENU | InputEvent.SHIFT_DOWN_MASK) + ")");
        filterButton.addActionListener(e -> {
            if (!syncing) {
                setFilterVisible(filterButton.isSelected());
                if (filterVisible) {
                    filterPane.focusQuery();
                }
            }
        });
        tb.add(filterButton);
        tb.addSeparator();
        tb.add(new JLabel("Highlighting: "));
        profileCombo.setMaximumSize(new java.awt.Dimension(200, profileCombo.getPreferredSize().height + 4));
        profileCombo.addActionListener(e -> {
            if (!syncing && profileCombo.getSelectedItem() instanceof String name) {
                setTabProfile(name);
            }
        });
        tb.add(profileCombo);
        JButton rules = new JButton("Rules…");
        rules.setToolTipText("Edit highlight rules");
        rules.addActionListener(e -> editHighlights(null));
        tb.add(rules);
        return tb;
    }

    private void buildEncodingMenu(JMenu menu) {
        menu.removeAll();
        LogTab t = current();
        Charset forced = t == null ? null : t.file().forcedCharset();
        ButtonGroup g = new ButtonGroup();
        JRadioButtonMenuItem auto = new JRadioButtonMenuItem("Auto-detect", forced == null);
        auto.addActionListener(e -> withTab(x -> x.setCharset(null)));
        g.add(auto);
        menu.add(auto);
        menu.addSeparator();
        for (String name : COMMON_CHARSETS) {
            Charset cs = charsetOrNull(name, null);
            if (cs == null) {
                continue;
            }
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(cs.name(), cs.equals(forced));
            it.addActionListener(e -> withTab(x -> x.setCharset(cs)));
            g.add(it);
            menu.add(it);
        }
        menu.addSeparator();
        menu.add(item("Other…", () -> {
            String[] all = Charset.availableCharsets().keySet().toArray(new String[0]);
            Object sel = JOptionPane.showInputDialog(this, "Charset:", "Encoding", JOptionPane.PLAIN_MESSAGE, null,
                    all, forced != null ? forced.name() : "UTF-8");
            if (sel != null) {
                withTab(x -> x.setCharset(Charset.forName(sel.toString())));
            }
        }));
        for (var c : menu.getMenuComponents()) {
            c.setEnabled(t != null);
        }
    }

    private void setTabProfile(String name) {
        withTab(t -> t.setProfile(name, highlighter(name)));
        updateControls();
    }

    private void cycleTab(int delta) {
        int n = tabs.getTabCount();
        if (n > 1) {
            tabs.setSelectedIndex((tabs.getSelectedIndex() + delta + n) % n);
        }
    }

    /** The log view with keyboard focus (main or filter pane), else the current tab's main view. */
    private LogView focusedView() {
        if (java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof LogView v) {
            return v;
        }
        LogTab t = current();
        return t == null ? null : t.view();
    }

    private void copySelection() {
        LogView v = focusedView();
        if (v == null) {
            return;
        }
        try {
            String text = v.selectedText();
            if (text != null) {
                copyToClipboard(text);
            }
        } catch (IllegalStateException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Copy", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void copyToClipboard(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private void goToLineDialog() {
        LogTab t = current();
        if (t == null) {
            return;
        }
        long n = t.view().lineCount();
        String s = JOptionPane.showInputDialog(this, "Line number (1 – " + Format.count(n) + "):",
                "Go to Line", JOptionPane.PLAIN_MESSAGE);
        if (s == null || s.isBlank()) {
            return;
        }
        try {
            long line = Long.parseLong(s.trim().replace(",", "").replace(".", "").replace("_", ""));
            t.view().goToLine(line - 1, true);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Not a line number: " + s, "Go to Line", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void ruleFromSelection() {
        LogTab t = current();
        String sel = t != null && t.view().hasSelection() ? t.view().selectedText() : null;
        HighlightRule r = new HighlightRule();
        if (sel != null && sel.indexOf('\n') < 0 && sel.indexOf('\r') < 0) {
            r.setPattern(sel);
            r.setName(sel.length() > 30 ? sel.substring(0, 30) + "…" : sel);
            r.setRegex(false);
            r.setCaseSensitive(true);
        }
        r.setBackground(new java.awt.Color(0xC8E6FF));
        editHighlights(r);
    }

    private void saveTabsAsFileSet() {
        List<LogTab> open = allTabs();
        if (open.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no open files.", "File Sets",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Name of the new file set (" + open.size() + " files):",
                "Save File Set", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        name = name.trim();
        FileSet set = new FileSet(name, openTabEntries(false));
        String n = name;
        int existing = -1;
        for (int i = 0; i < settings.fileSets.size(); i++) {
            if (settings.fileSets.get(i).getName().equals(n)) {
                existing = i;
            }
        }
        if (existing >= 0) {
            int ok = JOptionPane.showConfirmDialog(this, "Replace the existing file set “" + name + "”?",
                    "Save File Set", JOptionPane.OK_CANCEL_OPTION);
            if (ok != JOptionPane.OK_OPTION) {
                return;
            }
            settings.fileSets.set(existing, set);
        } else {
            settings.fileSets.add(set);
        }
        saveSettings();
    }

    private void showShortcuts() {
        String text = """
                Scrolling and navigation
                  Up / Down                 move current line
                  Ctrl+Up / Ctrl+Down       scroll one row
                  Page Up / Page Down       page
                  Home / End                start / end of file (End starts following)
                  Left / Right              scroll horizontally (no wrap)
                  Mouse wheel               scroll; Shift+wheel horizontal; Ctrl+wheel zoom
                  Alt+Down / Alt+Up         next / previous line colored by a line rule
                  Ctrl+G                    go to line
                  F2 / Shift+F2             next / previous bookmark
                  Ctrl+F2, click on gutter  toggle bookmark

                Search and selection
                  Ctrl+F                    find (Enter / Shift+Enter in the field)
                  F3 / Shift+F3             find next / previous
                  drag, double/triple click select text, word, line
                  Ctrl+C                    copy selection (or current line)

                Files and view
                  Ctrl+O, drag and drop     open files
                  Ctrl+W, middle click      close tab
                  Ctrl+PgDn / Ctrl+PgUp     next / previous tab
                  Ctrl+T                    follow tail
                  Alt+Z                     word wrap
                  F5                        reload file
                  Ctrl+= / Ctrl+- / Ctrl+0  zoom
                """;
        javax.swing.JTextArea area = new javax.swing.JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JOptionPane.showMessageDialog(this, area, "Keyboard Shortcuts", JOptionPane.PLAIN_MESSAGE);
    }

    // ------------------------------------------------------------------ helpers

    static Charset charsetOrNull(String name, Charset fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Charset.forName(name.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String keyText(int key, int modifiers) {
        String mods = InputEvent.getModifiersExText(modifiers);
        if (key == 0) {
            return mods.isEmpty() ? "" : mods + "+";
        }
        return (mods.isEmpty() ? "" : mods + "+") + KeyEvent.getKeyText(key);
    }

    private static Action action(String name, KeyStroke ks, Runnable r) {
        Action a = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
        if (ks != null) {
            a.putValue(Action.ACCELERATOR_KEY, ks);
        }
        return a;
    }

    private static JMenuItem item(String name, KeyStroke ks, Runnable r) {
        return new JMenuItem(action(name, ks, r));
    }

    private static JMenuItem item(String name, Runnable r) {
        return item(name, null, r);
    }

    private static void onShow(JMenu menu, Runnable r) {
        // build once now so accelerators of dynamic menus work before the menu is first opened
        r.run();
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                r.run();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
    }

    /** Opens files dropped onto the window. */
    final class FileDropHandler extends TransferHandler {
        /** Linux file managers offer only a URI list. */
        private final DataFlavor uriList = uriListFlavor();

        private static DataFlavor uriListFlavor() {
            try {
                return new DataFlavor("text/uri-list;class=java.lang.String");
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    || uriList != null && support.isDataFlavorSupported(uriList);
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                List<Path> paths = new ArrayList<>();
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    files.forEach(f -> paths.add(f.toPath()));
                } else {
                    String data = (String) support.getTransferable().getTransferData(uriList);
                    for (String line : data.split("\\R")) {
                        line = line.trim();
                        if (line.startsWith("file:")) {
                            paths.add(Path.of(java.net.URI.create(line)));
                        }
                    }
                }
                List<Path> files = paths.stream().filter(Files::isRegularFile).toList();
                SwingUtilities.invokeLater(() -> openPaths(files));
                return !files.isEmpty();
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ import / export

    private void exportSettings() {
        JFileChooser fc = new JFileChooser(settings.lastDirectory);
        fc.setDialogTitle("Export Settings");
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), "tailor-settings.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = fc.getSelectedFile().toPath();
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "Overwrite " + target + "?", "Export",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        captureSession();
        try {
            new SettingsStore(target).save(settings);
            JOptionPane.showMessageDialog(this, "Settings exported to\n" + target, "Export",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Cannot write " + target + ":\n" + e.getMessage(), "Export",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importSettings() {
        JFileChooser fc = new JFileChooser(settings.lastDirectory);
        fc.setDialogTitle("Import Settings");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = fc.getSelectedFile().toPath();
        Settings in;
        try {
            in = SettingsStore.read(source);
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(this, "Cannot read " + source + ":\n" + e.getMessage(), "Import",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        javax.swing.JCheckBox profiles = new javax.swing.JCheckBox("Highlight profiles (" + in.profiles.size()
                + ") – replaces profiles with the same name", true);
        javax.swing.JCheckBox sets = new javax.swing.JCheckBox("File sets (" + in.fileSets.size()
                + ") – replaces file sets with the same name", true);
        javax.swing.JCheckBox general = new javax.swing.JCheckBox("View, file and appearance settings", false);
        javax.swing.JCheckBox history = new javax.swing.JCheckBox("Find and filter history", false);
        Object[] message = {"Import from " + source.getFileName() + ":", profiles, sets, general, history};
        if (JOptionPane.showConfirmDialog(this, message, "Import Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        SettingsStore.merge(in, settings, profiles.isSelected(), sets.isSelected(), general.isSelected(),
                history.isSelected());
        if (general.isSelected()) {
            applyTheme();
        } else {
            applySettings();
        }
        saveSettings();
    }
}
