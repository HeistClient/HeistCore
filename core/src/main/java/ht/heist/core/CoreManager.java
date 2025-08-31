/*
 * =============================================================================
 *  FILE: CoreManager.java
 *  LOCATION: core/src/main/java/ht/heist/core
 *
 *  PURPOSE
 *  -----------------------------------------------------------------------------
 *  Central hub for Core services. Holds references and exposes small helpers:
 *    • Toggle heatmap (enable/disable)
 *    • Export heatmap snapshot (PNG)
 *    • Start/stop/reset the analytics-only InputRecorder (optional)
 *
 *  CHANGELOG (this revision)
 *  -----------------------------------------------------------------------------
 *  - REMOVED WorldService dependency to avoid binding/impl requirements.
 *    (We can add it back later when you actually use it.)
 *
 *  DESIGN
 *  -----------------------------------------------------------------------------
 *  - @Singleton + @Inject constructor for DI.
 *  - All recorder helpers are null-safe: if not bound, they no-op gracefully.
 * =============================================================================
 */

package ht.heist.core;

import ht.heist.core.impl.HumanizerServiceImpl;
import ht.heist.core.impl.MouseServiceImpl;
import ht.heist.core.services.CameraService;
import ht.heist.core.services.HeatmapService;
import ht.heist.core.services.InputRecorder;
import ht.heist.core.services.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CoreManager
{
    // -------------------------------------------------------------------------
    // Logger (explicit SLF4J; avoids Lombok)
    // -------------------------------------------------------------------------
    private static final Logger log = LoggerFactory.getLogger(CoreManager.class);

    // -------------------------------------------------------------------------
    // Core services (final = immutable after DI)
    // -------------------------------------------------------------------------
    private final MouseServiceImpl mouseService;
    private final CameraService cameraService;
    private final HumanizerServiceImpl humanizerService;
    private final HeatmapService heatmapService;
    private final InventoryService inventoryService;

    // Optional analytics-only recorder (may be null if not wired)
    private final InputRecorder inputRecorder;

    // =========================================================================
    // Construction (DI)
    // =========================================================================

    /**
     * Preferred DI constructor. WorldService removed in this revision.
     */
    @Inject
    public CoreManager(
            MouseServiceImpl mouseService,
            CameraService cameraService,
            HumanizerServiceImpl humanizerService,
            HeatmapService heatmapService,
            InventoryService inventoryService,
            InputRecorder inputRecorder // can be null if you don't bind it yet
    ) {
        this.mouseService = mouseService;
        this.cameraService = cameraService;
        this.humanizerService = humanizerService;
        this.heatmapService = heatmapService;
        this.inventoryService = inventoryService;
        this.inputRecorder = inputRecorder;
    }

    // =========================================================================
    // Getters (documented)
    // =========================================================================

    /** @return Core mouse service (exposes humanClick, tap hook, etc.) */
    public MouseServiceImpl getMouseService() { return mouseService; }

    /** @return camera control service (stub or real) */
    public CameraService getCameraService() { return cameraService; }

    /** @return humanizer (randomized delays, sleeps) */
    public HumanizerServiceImpl getHumanizerService() { return humanizerService; }

    /** @return heatmap backend (addClick, enable/disable, export) */
    public HeatmapService getHeatmapService() { return heatmapService; }

    /** @return inventory helper service */
    public InventoryService getInventoryService() { return inventoryService; }

    // =========================================================================
    // Heatmap helpers (single point to toggle/export)
    // =========================================================================

    /** Enable or disable the heatmap overlay via the service. */
    public void applyHeatmapVisibility(boolean show)
    {
        if (show) { heatmapService.enable(); log.debug("Heatmap enabled"); }
        else      { heatmapService.disable(); log.debug("Heatmap disabled"); }
    }

    /** Export a one-off PNG snapshot (safe-guarded). */
    public void exportHeatmapSnapshot()
    {
        try {
            heatmapService.exportSnapshot();
            log.info("Heatmap snapshot exported");
        } catch (Exception ex) {
            log.warn("Heatmap export failed", ex);
        }
    }

    // =========================================================================
    // Recorder helpers (analytics-only, null-safe)
    // =========================================================================

    /** Start/stop recorder by boolean; no-op if not bound. */
    public void setRecorderEnabled(boolean enable, String activityTag)
    {
        if (inputRecorder == null) {
            log.debug("InputRecorder not bound; setRecorderEnabled({}) ignored", enable);
            return;
        }
        if (enable) {
            if (!inputRecorder.isRecording()) {
                inputRecorder.start(activityTag);
                log.info("Recorder started (activity={})", activityTag);
            }
        } else {
            if (inputRecorder.isRecording()) {
                inputRecorder.stopAndSave();
                log.info("Recorder stopped and saved");
            }
        }
    }

    /** @return true if recorder exists and is active. */
    public boolean isRecorderRecording()
    {
        return inputRecorder != null && inputRecorder.isRecording();
    }

    /** Explicitly start recorder; no-op if missing/already running. */
    public void startRecorder(String activityTag)
    {
        if (inputRecorder == null) { log.debug("InputRecorder not bound; start ignored"); return; }
        if (!inputRecorder.isRecording()) {
            inputRecorder.start(activityTag);
            log.info("Recorder started (activity={})", activityTag);
        }
    }

    /** Explicitly stop recorder and persist; no-op if missing/already stopped. */
    public void stopRecorder()
    {
        if (inputRecorder == null) { log.debug("InputRecorder not bound; stop ignored"); return; }
        if (inputRecorder.isRecording()) {
            inputRecorder.stopAndSave();
            log.info("Recorder stopped and saved");
        }
    }

    /** Clear analytics; no-op if recorder missing. */
    public void resetRecorderData()
    {
        if (inputRecorder == null) { log.debug("InputRecorder not bound; reset ignored"); return; }
        inputRecorder.resetData();
        log.info("Recorder data reset");
    }
}
