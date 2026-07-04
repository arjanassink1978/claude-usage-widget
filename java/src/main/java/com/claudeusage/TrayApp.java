package com.claudeusage;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
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
        Image icon = makeIconImage();
        PopupMenu menu = new PopupMenu();

        MenuItem open = new MenuItem("Open");
        open.addActionListener(e -> Platform.runLater(TrayApp::toggleStage));
        menu.add(open);

        MenuItem refreshItem = new MenuItem("Refresh");
        refreshItem.addActionListener(e -> new Thread(TrayApp::refresh).start());
        menu.add(refreshItem);

        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> {
            Platform.exit();
            System.exit(0);
        });
        menu.add(quit);

        trayIcon = new TrayIcon(icon, "Claude usage", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> Platform.runLater(TrayApp::toggleStage));
        SystemTray.getSystemTray().add(trayIcon);
    }

    private static void toggleStage() {
        if (stage.isShowing()) {
            stage.hide();
        } else {
            stage.show();
            stage.toFront();
        }
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
        if (trayIcon != null) trayIcon.setToolTip(tooltip.toString());

        String html = UsageCore.renderHtml(account, totals, active, stale, BRIDGE_JS);
        if (stage != null && stage.getScene() != null) {
            Platform.runLater(() -> {
                WebView webView = (WebView) stage.getScene().getRoot();
                webView.getEngine().loadContent(html);
            });
        }
    }

    private static Image makeIconImage() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(218, 120, 87));
        g.setStroke(new java.awt.BasicStroke(4, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        int cx = size / 2, cy = size / 2, r = size / 2 - 3;
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * i / 3;
            int x = (int) (cx + r * Math.cos(angle));
            int y = (int) (cy + r * Math.sin(angle));
            g.drawLine(cx, cy, x, y);
        }
        g.dispose();
        return img;
    }
}
