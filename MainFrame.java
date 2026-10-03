package com.compressor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.zip.Deflater;

public class MainFrame extends JFrame {

    private static final File LOG_FILE =
            new File(System.getProperty("user.home"), "compressor_log.txt");

    // Compress tab
    private final DefaultListModel<File> listModel = new DefaultListModel<>();
    private final JList<File> fileList = new JList<>(listModel);
    private final JTextField zipOutField = new JTextField();
    private final JComboBox<String> levelBox =
            new JComboBox<>(new String[]{"Store (0)", "Fast (1)", "Normal (6)", "Best (9)"});
    private final JButton compressBtn = new JButton("Compress");

    // Extract tab
    private final JTextField zipInField = new JTextField();
    private final JTextField destField = new JTextField();
    private final JButton extractBtn = new JButton("Extract");

    // Shared
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JButton cancelBtn = new JButton("Cancel");
    private final JTextArea logArea = new JTextArea();

    private ZipEngine engine;

    public MainFrame() {
        super("File Compressor & Decompressor");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(780, 640);
        setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Compress", buildCompressTab());
        tabs.addTab("Extract", buildExtractTab());

        progressBar.setStringPainted(true);
        cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> { if (engine != null) engine.cancel(); });

        JPanel progressRow = new JPanel(new BorderLayout(8, 0));
        progressRow.add(progressBar, BorderLayout.CENTER);
        progressRow.add(cancelBtn, BorderLayout.EAST);
        JPanel progressPanel = new JPanel(new BorderLayout(0, 4));
        progressPanel.add(progressRow, BorderLayout.CENTER);
        progressPanel.add(statusLabel, BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log (saved to " + LOG_FILE + ")"));
        logScroll.setPreferredSize(new Dimension(100, 220));

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.add(progressPanel, BorderLayout.NORTH);
        bottom.add(logScroll, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(tabs, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
    }

    // ------------------------------------------------------------ Compress tab
    private JPanel buildCompressTab() {
        fileList.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JScrollPane scroll = new JScrollPane(fileList);
        scroll.setBorder(BorderFactory.createTitledBorder("Files & folders to compress (drag & drop supported)"));

        // Drag & drop
        fileList.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    files.forEach(MainFrame.this::addIfNew);
                    return true;
                } catch (Exception ex) { return false; }
            }
        });

        JButton addFiles = new JButton("Add Files...");
        JButton addFolder = new JButton("Add Folder...");
        JButton remove = new JButton("Remove Selected");
        JButton clear = new JButton("Clear");
        addFiles.addActionListener(e -> chooseInputs(JFileChooser.FILES_ONLY));
        addFolder.addActionListener(e -> chooseInputs(JFileChooser.DIRECTORIES_ONLY));
        remove.addActionListener(e -> {
            for (File f : new ArrayList<>(fileList.getSelectedValuesList())) listModel.removeElement(f);
        });
        clear.addActionListener(e -> listModel.clear());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(addFiles); buttons.add(addFolder); buttons.add(remove); buttons.add(clear);

        JButton browseOut = new JButton("Browse...");
        browseOut.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("archive.zip"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (!f.getName().toLowerCase().endsWith(".zip"))
                    f = new File(f.getParentFile(), f.getName() + ".zip");
                zipOutField.setText(f.getAbsolutePath());
            }
        });
        levelBox.setSelectedIndex(2);
        compressBtn.addActionListener(e -> startCompress());

        JPanel out = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3); c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0; out.add(new JLabel("Output ZIP:"), c);
        c.gridx = 1; c.weightx = 1; out.add(zipOutField, c);
        c.gridx = 2; c.weightx = 0; out.add(browseOut, c);
        c.gridx = 0; c.gridy = 1; out.add(new JLabel("Level:"), c);
        c.gridx = 1; out.add(levelBox, c);
        c.gridx = 2; out.add(compressBtn, c);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        p.add(buttons, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.add(out, BorderLayout.SOUTH);
        return p;
    }

    // ------------------------------------------------------------ Extract tab
    private JPanel buildExtractTab() {
        JButton browseZip = new JButton("Browse...");
        JButton browseDest = new JButton("Browse...");
        browseZip.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ZIP archives", "zip"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                zipInField.setText(f.getAbsolutePath());
                if (destField.getText().isEmpty()) {
                    String base = f.getName().replaceAll("(?i)\\.zip$", "");
                    destField.setText(new File(f.getParentFile(), base).getAbsolutePath());
                }
            }
        });
        browseDest.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                destField.setText(fc.getSelectedFile().getAbsolutePath());
        });
        extractBtn.addActionListener(e -> startExtract());

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(16, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 4); c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0; p.add(new JLabel("ZIP file:"), c);
        c.gridx = 1; c.weightx = 1; p.add(zipInField, c);
        c.gridx = 2; c.weightx = 0; p.add(browseZip, c);
        c.gridx = 0; c.gridy = 1; p.add(new JLabel("Extract to:"), c);
        c.gridx = 1; p.add(destField, c);
        c.gridx = 2; p.add(browseDest, c);
        c.gridx = 2; c.gridy = 2; p.add(extractBtn, c);
        c.gridx = 0; c.gridy = 3; c.weighty = 1; c.gridwidth = 3; c.fill = GridBagConstraints.BOTH;
        p.add(Box.createVerticalGlue(), c);
        return p;
    }

    // ------------------------------------------------------------ Actions
    private void chooseInputs(int mode) {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(mode);
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            for (File f : fc.getSelectedFiles()) addIfNew(f);
    }

    private void addIfNew(File f) {
        if (!listModel.contains(f)) listModel.addElement(f);
    }

    private int selectedLevel() {
        switch (levelBox.getSelectedIndex()) {
            case 0: return Deflater.NO_COMPRESSION;
            case 1: return Deflater.BEST_SPEED;
            case 3: return Deflater.BEST_COMPRESSION;
            default: return 6;
        }
    }

    private void startCompress() {
        if (listModel.isEmpty()) { warn("Add at least one file or folder first."); return; }
        if (zipOutField.getText().trim().isEmpty()) { warn("Choose an output ZIP file."); return; }
        File zip = new File(zipOutField.getText().trim());
        if (zip.exists() && JOptionPane.showConfirmDialog(this,
                zip.getName() + " already exists. Overwrite?", "Confirm",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;

        List<File> inputs = Collections.list(listModel.elements());
        runTask("COMPRESS", e -> engine.compress(inputs, zip, selectedLevel(), pl(), this::log));
    }

    private void startExtract() {
        File zip = new File(zipInField.getText().trim());
        if (!zip.isFile()) { warn("Choose a valid ZIP file."); return; }
        if (destField.getText().trim().isEmpty()) { warn("Choose a destination folder."); return; }
        File dest = new File(destField.getText().trim());
        runTask("EXTRACT", e -> engine.extract(zip, dest, pl(), this::log));
    }

    private interface Job { ZipEngine.Stats run(ZipEngine e) throws Exception; }

    private ZipEngine.ProgressListener pl() {
        return (done, total, name) -> SwingUtilities.invokeLater(() -> {
            int pct = total <= 0 ? 100 : (int) (done * 100 / total);
            progressBar.setValue(pct);
            statusLabel.setText(name + "  (" + ZipEngine.format(done) + " / " + ZipEngine.format(total) + ")");
        });
    }

    private void runTask(String type, Job job) {
        engine = new ZipEngine();
        setBusy(true);
        progressBar.setValue(0);
        log("=== " + type + " started ===");
        new SwingWorker<ZipEngine.Stats, Void>() {
            @Override protected ZipEngine.Stats doInBackground() throws Exception {
                return job.run(engine);
            }
            @Override protected void done() {
                try {
                    ZipEngine.Stats s = get();
                    if (!s.cancelled) progressBar.setValue(100);
                    logStats(type, s);
                    statusLabel.setText(s.cancelled ? "Cancelled" : "Done");
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    log("ERROR: " + t.getMessage());
                    statusLabel.setText("Failed");
                    JOptionPane.showMessageDialog(MainFrame.this, t.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void logStats(String type, ZipEngine.Stats s) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(type).append(s.cancelled ? " CANCELLED" : " COMPLETE").append(" ---\n");
        sb.append("Files processed : ").append(s.fileCount).append('\n');
        if (type.equals("COMPRESS")) {
            sb.append("Original size   : ").append(ZipEngine.format(s.originalBytes)).append('\n');
            sb.append("Compressed size : ").append(ZipEngine.format(s.compressedBytes)).append('\n');
            sb.append(String.format("Space saved     : %.1f%%%n", s.ratioPercent()));
        } else {
            sb.append("Archive size    : ").append(ZipEngine.format(s.compressedBytes)).append('\n');
            sb.append("Extracted size  : ").append(ZipEngine.format(s.originalBytes)).append('\n');
        }
        double sec = s.elapsedMs / 1000.0;
        sb.append(String.format("Time taken      : %.2f s%n", sec));
        if (sec > 0 && s.originalBytes > 0)
            sb.append(String.format("Speed           : %s/s%n",
                    ZipEngine.format((long) (s.originalBytes / sec))));
        log(sb.toString().trim());
    }

    private void setBusy(boolean busy) {
        compressBtn.setEnabled(!busy);
        extractBtn.setEnabled(!busy);
        cancelBtn.setEnabled(busy);
    }

    private void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + msg;
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
        try {
            Files.write(LOG_FILE.toPath(),
                    (new SimpleDateFormat("yyyy-MM-dd ").format(new Date()) + line + System.lineSeparator())
                            .getBytes("UTF-8"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    private void warn(String m) {
        JOptionPane.showMessageDialog(this, m, "Notice", JOptionPane.WARNING_MESSAGE);
    }
}
