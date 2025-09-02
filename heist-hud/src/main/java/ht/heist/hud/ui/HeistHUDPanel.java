// ============================================================================
// FILE: HeistHUDPanel.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.ui
// -----------------------------------------------------------------------------
// TITLE
//   HeistHUDPanel — side panel for Clear / Export / Open Folder
//
// PURPOSE
//   - Buttons to manage the in-memory store and export density PNGs.
//   - Keeps UI dead simple and robust across RuneLite versions.
// ============================================================================

package ht.heist.hud.ui;

import ht.heist.corejava.api.input.TapEvent;
import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.export.HeatmapExporter;
import ht.heist.hud.service.ClickStore;
import net.runelite.api.Client;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

public final class HeistHUDPanel extends PluginPanel
{
    private final HeistHUDConfig cfg;
    private final ClickStore store;
    private final HeatmapExporter exporter;
    private final Client client;

    @Inject
    public HeistHUDPanel(HeistHUDConfig cfg, ClickStore store, HeatmapExporter exporter, Client client)
    {
        super(false);
        this.cfg = cfg; this.store = store; this.exporter = exporter; this.client = client;

        setLayout(new BorderLayout(0, 8));
        setBackground(new Color(24,24,24));

        JLabel title = new JLabel("Heist HUD");
        title.setForeground(Color.WHITE);
        title.setBorder(BorderFactory.createEmptyBorder(8,8,0,8));

        JPanel buttons = new JPanel(new GridLayout(0,1,8,8));
        buttons.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        buttons.setBackground(new Color(32,32,32));

        JButton clear = new JButton("Clear Persistent (Overlay/PNG)");
        clear.addActionListener(e -> {
            store.clear();
            JOptionPane.showMessageDialog(this, "Cleared in-memory store (overlay/PNG).", "Heist HUD", JOptionPane.INFORMATION_MESSAGE);
        });

        JButton exportPng = new JButton("Export Density PNG");
        exportPng.addActionListener(e -> {
            int w = client.getCanvas() != null ? client.getCanvas().getWidth() : 765;
            int h = client.getCanvas() != null ? client.getCanvas().getHeight() : 503;
            File outDir = expandOutputDir(cfg.outputDir());
            List<TapEvent> events = store.getAll();
            String path = exporter.exportTo(outDir, events, cfg.palette(), Math.max(1, cfg.dotRadiusPx()), w, h);
            JOptionPane.showMessageDialog(this,
                    path != null ? ("Exported to:\n" + path) : "Export failed.",
                    "Heist HUD", path != null ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
        });

        JButton openFolder = new JButton("Open Output Folder");
        openFolder.addActionListener(e -> {
            File dir = expandOutputDir(cfg.outputDir());
            if (!dir.exists()) dir.mkdirs();
            try { Desktop.getDesktop().open(dir); }
            catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Folder: " + dir.getAbsolutePath(), "Heist HUD", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        buttons.add(clear);
        buttons.add(exportPng);
        buttons.add(openFolder);

        add(title, BorderLayout.NORTH);
        add(buttons, BorderLayout.CENTER);
    }

    private static File expandOutputDir(String raw) {
        String home = System.getProperty("user.home");
        return new File(raw.replace("%USERPROFILE%", home));
    }
}
