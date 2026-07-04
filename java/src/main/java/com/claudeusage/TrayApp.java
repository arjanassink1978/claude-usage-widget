package com.claudeusage;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

public class TrayApp {
    private static volatile TrayIcon trayIcon;
    private static volatile Stage stage;
    private static volatile UsageCore.AccountUsage lastAccount;
    private static final Supplier<String> tokenSupplier = TokenProvider.forCurrentPlatform();

    private static final String BRIDGE_JS =
            "function bridge(msg){" +
            "if(msg==='refresh'){javaBridge.refresh();}" +
            "else if(msg==='quit'){javaBridge.quit();}" +
            "}";

    public static class JsBridge {
        public void refresh() {
            new Thread(TrayApp::refresh, "manual-refresh").start();
        }

        public void quit() {
            Platform.exit();
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        }
    }

    public static void main(String[] args) throws Exception {
        if (!SystemTray.isSupported()) {
            System.err.println("System tray not supported on this platform.");
            System.exit(1);
        }

        Platform.setImplicitExit(false);
        Platform.startup(TrayApp::buildStage);

        setupTray();

        Thread refreshLoop = new Thread(() -> {
            while (true) {
                refresh();
                try {
                    Thread.sleep(UsageCore.REFRESH_SECONDS * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "refresh-loop");
        refreshLoop.setDaemon(true);
        refreshLoop.start();
    }

    private static void buildStage() {
        stage = new Stage(StageStyle.UTILITY);
        stage.setAlwaysOnTop(true);
        stage.setWidth(400);
        stage.setHeight(440);

        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("javaBridge", new JsBridge());
            }
        });

        stage.setScene(new Scene(webView, 400, 440));
        stage.setOnCloseRequest(e -> {
            e.consume();
            stage.hide();
        });

        engine.loadContent(UsageCore.renderHtml(
                null, UsageCore.Totals.zero(), null, false, BRIDGE_JS));
    }

    private static void setupTray() throws AWTException {
        Image icon = makeIconImage(0);
        // No PopupMenu here on purpose: java.awt.TrayIcon shows an attached PopupMenu on the
        // same single click on macOS, which would swallow the click before this actionListener
        // toggles the window (Refresh/Quit already live in the popover's own footer links).
        trayIcon = new TrayIcon(icon, "Claude usage");
        trayIcon.setImageAutoSize(true);
        // actionListener's click firing is unreliable on macOS AWT (long-standing JDK bug);
        // MouseListener.mouseClicked is the reliable cross-platform way to detect a tray click.
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                double x = e.getXOnScreen();
                double y = e.getYOnScreen();
                Platform.runLater(() -> toggleStageAt(x, y));
            }
        });
        SystemTray.getSystemTray().add(trayIcon);
    }

    /** Anchors the popover under (macOS menu bar) or above (Windows taskbar) the clicked icon. */
    private static void toggleStageAt(double clickX, double clickY) {
        if (stage.isShowing()) {
            stage.hide();
            return;
        }
        boolean macOS = System.getProperty("os.name", "").toLowerCase().contains("mac");
        double w = stage.getWidth() > 0 ? stage.getWidth() : 400;
        double h = stage.getHeight() > 0 ? stage.getHeight() : 440;
        double x = clickX - w / 2;
        double y = macOS ? clickY + 8 : clickY - h - 8;
        stage.setX(x);
        stage.setY(y);
        stage.show();
        stage.toFront();
    }

    private static void refresh() {
        UsageCore.Totals totals = UsageCore.scanUsage();
        UsageCore.ActiveSession active = UsageCore.scanActiveSession();
        UsageCore.AccountUsage fetched = UsageCore.fetchAccountUsage(tokenSupplier);
        if (fetched != null) lastAccount = fetched;
        UsageCore.AccountUsage account = lastAccount;
        boolean stale = fetched == null && account != null;

        StringBuilder tooltip = new StringBuilder("Claude usage");
        if (account != null) {
            if (account.fiveHour() != null) {
                tooltip.append(String.format(" · Session %.0f%%", account.fiveHour().utilization()));
            }
            if (account.sevenDay() != null) {
                tooltip.append(String.format(" · Weekly %.0f%%", account.sevenDay().utilization()));
            }
        }
        if (trayIcon != null) {
            trayIcon.setToolTip(tooltip.toString());
            double pct = 0;
            if (account != null) {
                if (account.fiveHour() != null) pct = Math.max(pct, account.fiveHour().utilization());
                if (account.sevenDay() != null) pct = Math.max(pct, account.sevenDay().utilization());
            }
            trayIcon.setImage(makeIconImage(pct));
        }

        String html = UsageCore.renderHtml(account, totals, active, stale, BRIDGE_JS);
        if (stage != null && stage.getScene() != null) {
            Platform.runLater(() -> {
                WebView webView = (WebView) stage.getScene().getRoot();
                webView.getEngine().loadContent(html);
            });
        }
    }

    private static final Color RING_TRACK = new Color(120, 120, 120, 90);
    private static final Color IDLE_COLOR = new Color(150, 150, 150);
    private static final Color ACTIVE_COLOR = new Color(224, 122, 63);

    /** Draws a "C" logo with a circular progress ring around it, filled clockwise from the top by pct. */
    private static Image makeIconImage(double pct) {
        int size = 128;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double clamped = Math.max(0, Math.min(100, pct));
        Color color = clamped <= 0 ? IDLE_COLOR : ACTIVE_COLOR;

        int strokeWidth = size / 7;
        int margin = strokeWidth / 3;
        Ellipse2D.Double bounds = new Ellipse2D.Double(margin, margin, size - 2.0 * margin, size - 2.0 * margin);

        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(RING_TRACK);
        g.draw(bounds);

        if (clamped > 0) {
            g.setColor(color);
            double sweep = clamped / 100.0 * 360.0;
            Arc2D.Double arc = new Arc2D.Double(bounds.getBounds2D(), 90, -sweep, Arc2D.OPEN);
            g.draw(arc);
        }

        g.setColor(color);
        Font font = new Font("SansSerif", Font.BOLD, (int) (size * 0.55));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        String letter = "C";
        int tx = (size - fm.stringWidth(letter)) / 2;
        int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(letter, tx, ty);

        g.dispose();
        return img;
    }
}
