import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;   // ✅ added
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;            // ✅ added
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * URL Shortener Swing App with a tiny embedded HTTP redirect server.
 * Short links look like: http://localhost:8080/<code>
 *
 * Compile: javac UrlShortenerApp.java
 * Run:     java UrlShortenerApp
 *
 * Requires: Java 8+
 */
public class UrlShortenerApp extends JFrame {

    // --- Storage ---
    private final Map<String, String> codeToUrl = new HashMap<>();
    private final Map<String, String> urlToCode = new HashMap<>();

    // --- UI ---
    private JTextField urlField;
    private JTextField aliasField;
    private JTextField shortField;
    private JTextField searchField;
    private JLabel statusLabel;
    private JTable table;
    private DefaultTableModel model;

    // --- Server / Config ---
    private HttpServer server;
    private int port = 8080;
    //private final String baseUrl = () -> "http://localhost:" + port;
    private static final String CSV_PATH = "urls.csv";

    public UrlShortenerApp() {
        super("URL Shortener (Swing + Local Redirect Server)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 560));
        setLocationRelativeTo(null);
        buildUI();
        attachHandlers();
        loadFromCsv();
        startServer();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                stopServer();
                saveToCsv();
            }
        });
        setVisible(true);
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Top Form Panel
        JPanel form = new JPanel();
        form.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.gridx = 0; gc.gridy = 0; gc.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel("Long URL:"), gc);
        gc.gridx = 1; gc.gridy = 0; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        urlField = new JTextField();
        form.add(urlField, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0; gc.fill = GridBagConstraints.NONE; gc.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel("Custom alias (optional):"), gc);
        gc.gridx = 1; gc.gridy = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        aliasField = new JTextField();
        form.add(aliasField, gc);

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton shortenBtn = new JButton("Shorten");
        JButton copyBtn = new JButton("Copy");
        JButton openBtn = new JButton("Open");
        JButton clearBtn = new JButton("Clear");
        buttonsRow.add(shortenBtn);
        buttonsRow.add(copyBtn);
        buttonsRow.add(openBtn);
        buttonsRow.add(clearBtn);

        gc.gridx = 0; gc.gridy = 2; gc.anchor = GridBagConstraints.LINE_END; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        form.add(new JLabel("Short URL:"), gc);
        gc.gridx = 1; gc.gridy = 2; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        shortField = new JTextField();
        shortField.setEditable(false);
        form.add(shortField, gc);

        gc.gridx = 1; gc.gridy = 3; gc.fill = GridBagConstraints.NONE; gc.anchor = GridBagConstraints.LINE_START; gc.weightx = 0;
        form.add(buttonsRow, gc);

        // Middle: Table + Toolbar
        JPanel mid = new JPanel(new BorderLayout(8, 8));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton deleteBtn = new JButton("Delete");
        JButton exportBtn = new JButton("Export CSV");
        JButton pickPortBtn = new JButton("Change Port");
        toolbar.add(new JLabel("Search:"));
        searchField = new JTextField(24);
        toolbar.add(searchField);
        toolbar.add(deleteBtn);
        toolbar.add(exportBtn);
        toolbar.add(pickPortBtn);

        model = new DefaultTableModel(new Object[]{"Code", "Short URL", "Original URL", "Created At"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        table.setRowHeight(24);
        table.setAutoCreateRowSorter(true);

        mid.add(toolbar, BorderLayout.NORTH);
        mid.add(new JScrollPane(table), BorderLayout.CENTER);

        // Bottom: Status
        statusLabel = new JLabel(" Ready. Server: http://localhost:" + port + "/{code}");
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(new EmptyBorder(4, 8, 4, 8));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        root.add(form, BorderLayout.NORTH);
        root.add(mid, BorderLayout.CENTER);
        root.add(statusPanel, BorderLayout.SOUTH);

        setContentPane(root);

        // Actions
        shortenBtn.addActionListener(e -> onShorten());
        copyBtn.addActionListener(e -> onCopy());
        openBtn.addActionListener(e -> onOpen());
        clearBtn.addActionListener(e -> { urlField.setText(""); aliasField.setText(""); shortField.setText(""); urlField.requestFocus(); });
        deleteBtn.addActionListener(e -> onDelete());
        exportBtn.addActionListener(e -> onExport());
        pickPortBtn.addActionListener(e -> onChangePort());
    }

    private void attachHandlers() {
        // Press Enter to shorten
        urlField.addActionListener(e -> onShorten());
        aliasField.addActionListener(e -> onShorten());

        // Search filter
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        // Double click a row -> put short link into field
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        int modelRow = table.convertRowIndexToModel(row);
                        String shortUrl = (String) model.getValueAt(modelRow, 1);
                        shortField.setText(shortUrl);
                    }
                }
            }
        });
    }

    private void onShorten() {
        String longUrl = urlField.getText().trim();
        String alias = aliasField.getText().trim();

        if (longUrl.isEmpty()) {
            toast("Please enter a URL.");
            return;
        }
        String normalized = normalizeUrl(longUrl);
        if (normalized == null) {
            toast("Invalid URL. Include http:// or https://");
            return;
        }

        try {
            String code;
            if (!alias.isEmpty()) {
                if (!alias.matches("^[A-Za-z0-9_-]{3,32}$")) {
                    toast("Alias must be 3–32 chars: letters, numbers, _ or -");
                    return;
                }
                if (codeToUrl.containsKey(alias) && !normalized.equals(codeToUrl.get(alias))) {
                    toast("Alias already in use.");
                    return;
                }
                code = alias;
            } else {
                // Reuse existing code for same URL if present
                if (urlToCode.containsKey(normalized)) {
                    code = urlToCode.get(normalized);
                } else {
                    code = generateCode(normalized);
                    while (codeToUrl.containsKey(code)) {
                        code = generateCode(normalized + System.nanoTime());
                    }
                }
            }

            codeToUrl.put(code, normalized);
            urlToCode.put(normalized, code);
            String shortUrl = baseUrl() + "/" + code;
            shortField.setText(shortUrl);
            addOrUpdateRow(code, shortUrl, normalized);
            toast("Short link ready.");
        } catch (Exception ex) {
            ex.printStackTrace();
            toast("Error: " + ex.getMessage());
        }
    }

    private void onCopy() {
        String text = shortField.getText().trim();
        if (text.isEmpty()) { toast("Nothing to copy."); return; }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        toast("Copied to clipboard.");
    }

    private void onOpen() {
        String text = shortField.getText().trim();
        if (text.isEmpty()) { toast("Nothing to open."); return; }
        try {
            Desktop.getDesktop().browse(new URI(text));
        } catch (Exception ex) {
            toast("Cannot open link: " + ex.getMessage());
        }
    }

    private void onDelete() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) { toast("Select row(s) to delete."); return; }
        int confirm = JOptionPane.showConfirmDialog(this, "Delete selected short links?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        // Collect model rows first
        List<Integer> modelRows = new ArrayList<>();
        for (int r : rows) modelRows.add(table.convertRowIndexToModel(r));
        // Sort desc to avoid index shift
        modelRows.sort(Comparator.reverseOrder());

        for (int mr : modelRows) {
            String code = (String) model.getValueAt(mr, 0);
            String url  = (String) model.getValueAt(mr, 2);
            model.removeRow(mr);
            codeToUrl.remove(code);
            urlToCode.remove(url);
        }
        toast("Deleted.");
    }

    private void onExport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("urls_export_" + timeStamp() + ".csv"));
        int res = chooser.showSaveDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
                pw.println("code,short_url,original_url,created_at");
                for (int i = 0; i < model.getRowCount(); i++) {
                    String code = (String) model.getValueAt(i, 0);
                    String shortUrl = (String) model.getValueAt(i, 1);
                    String url = (String) model.getValueAt(i, 2);
                    String created = (String) model.getValueAt(i, 3);
                    pw.println(csv(code) + "," + csv(shortUrl) + "," + csv(url) + "," + csv(created));
                }
                toast("Exported: " + f.getAbsolutePath());
            } catch (Exception ex) {
                toast("Export failed: " + ex.getMessage());
            }
        }
    }

    private void onChangePort() {
        String input = JOptionPane.showInputDialog(this, "Enter port (1024–65535):", String.valueOf(port));
        if (input == null) return;
        try {
            int p = Integer.parseInt(input.trim());
            if (p < 1024 || p > 65535) throw new IllegalArgumentException("Port out of range");
            stopServer();
            port = p;
            startServer();
            // Update all short URLs in table
            for (int i = 0; i < model.getRowCount(); i++) {
                String code = (String) model.getValueAt(i, 0);
                model.setValueAt(baseUrl() + "/" + code, i, 1);
            }
            if (!shortField.getText().isEmpty()) {
                String code = shortField.getText().substring(shortField.getText().lastIndexOf('/') + 1);
                shortField.setText(baseUrl() + "/" + code);
            }
            statusLabel.setText(" Server restarted on http://localhost:" + port + "/{code}");
            toast("Port changed to " + port);
        } catch (Exception ex) {
            toast("Failed to change port: " + ex.getMessage());
        }
    }

    private void applyFilter() {
        String q = searchField.getText().trim().toLowerCase();
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        if (q.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(q)));
        }
    }

    // --- Helpers ---

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String normalizeUrl(String input) {
        try {
            // If no scheme, assume http
            if (!input.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) {
                input = "http://" + input;
            }
            URI uri = new URI(input).normalize();
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            // Rebuild URL (avoid default ports)
            URL url = uri.toURL();
            return url.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String generateCode(String data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest((data + "|salt:" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
        return toBase62(hash).substring(0, 7); // 7-char code
    }

    private static final char[] B62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private String toBase62(byte[] bytes) {
        // Convert the first 10 bytes to a positive BigInteger-like base62 (simple, fast)
        long acc = 0;
        for (int i = 0; i < Math.min(8, bytes.length); i++) {
            acc = (acc << 8) | (bytes[i] & 0xFFL);
        }
        StringBuilder sb = new StringBuilder();
        while (acc > 0) {
            int r = (int)(acc % 62);
            sb.append(B62[r]);
            acc /= 62;
        }
        if (sb.length() == 0) sb.append('0');
        return sb.reverse().toString() + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
    }

    private void addOrUpdateRow(String code, String shortUrl, String original) {
        // If exists, update timestamp if missing
        for (int i = 0; i < model.getRowCount(); i++) {
            if (code.equals(model.getValueAt(i, 0))) {
                model.setValueAt(shortUrl, i, 1);
                model.setValueAt(original, i, 2);
                return;
            }
        }
        model.addRow(new Object[]{code, shortUrl, original, timeStamp()});
    }

    private String timeStamp() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now());
    }

    private void toast(String msg) {
        statusLabel.setText(" " + msg);
        // Also show a brief balloon
        ToolTipManager.sharedInstance().setInitialDelay(0);
        statusLabel.setToolTipText(msg);
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // --- Persistence ---

    private void loadFromCsv() {
        File f = new File(CSV_PATH);
        if (!f.exists()) return;
        try (BufferedReader br = Files.newBufferedReader(Paths.get(CSV_PATH), StandardCharsets.UTF_8)) {
            String header = br.readLine(); // skip
            String line;
            while ((line = br.readLine()) != null) {
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 4) continue;
                String code = cols.get(0);
                String shortUrl = cols.get(1);
                String original = cols.get(2);
                String created = cols.get(3);
                codeToUrl.put(code, original);
                urlToCode.put(original, code);
                model.addRow(new Object[]{code, baseUrl() + "/" + code, original, created});
            }
            toast("Loaded " + codeToUrl.size() + " links.");
        } catch (Exception ex) {
            toast("Failed to load CSV: " + ex.getMessage());
        }
    }

    private void saveToCsv() {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(CSV_PATH), StandardCharsets.UTF_8))) {
            pw.println("code,short_url,original_url,created_at");
            for (int i = 0; i < model.getRowCount(); i++) {
                pw.println(csv((String) model.getValueAt(i, 0)) + "," +
                           csv((String) model.getValueAt(i, 1)) + "," +
                           csv((String) model.getValueAt(i, 2)) + "," +
                           csv((String) model.getValueAt(i, 3)));
            }
        } catch (Exception ex) {
            // best-effort save
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else { inQuotes = false; }
                } else sb.append(c);
            } else {
                if (c == ',') { out.add(sb.toString()); sb.setLength(0); }
                else if (c == '"') { inQuotes = true; }
                else sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    // --- Tiny HTTP Server ---

    private void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new RedirectHandler());
            server.setExecutor(null);
            server.start();
        } catch (BindException be) {
            toast("Port " + port + " in use. Trying 0 (random)...");
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
                port = server.getAddress().getPort();
                server.createContext("/", new RedirectHandler());
                server.start();
                toast("Server started on random port " + port);
            } catch (IOException e2) {
                toast("Failed to start server: " + e2.getMessage());
            }
        } catch (IOException e) {
            toast("Failed to start server: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private class RedirectHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.length() <= 1) {
                byte[] body = landingPage().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                return;
            }
            String code = path.substring(1);
            String target = codeToUrl.get(code);
            if (target != null) {
                ex.getResponseHeaders().add("Location", target);
                ex.sendResponseHeaders(301, -1);
                ex.close();
            } else {
                byte[] body = ("<h2>404 - Unknown short code</h2><p>No mapping for <b>" +
                        html(code) + "</b></p>").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(404, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            }
        }
    }

    private String landingPage() {
        return "<!doctype html><html><head><meta charset='utf-8'><title>Local URL Shortener</title></head>" +
               "<body style='font-family:Arial,Helvetica,sans-serif;padding:24px;'>" +
               "<h2>Local URL Shortener</h2>" +
               "<p>This is the local redirect server. Create links in the desktop app.</p>" +
               "</body></html>";
    }

    private String html(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    // --- Main ---
    public static void main(String[] args) {
        SwingUtilities.invokeLater(UrlShortenerApp::new);
    }
}
