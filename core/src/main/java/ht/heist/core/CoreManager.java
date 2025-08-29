package ht.heist.core;

import ht.heist.core.services.*;

public class CoreManager {
    private final MouseService mouseService;
    private final CameraService cameraService;
    private final WorldService worldService;
    private final HumanizerService humanizerService;
    private final HeatmapService heatmapService;

    public CoreManager(MouseService mouseService,
                       CameraService cameraService,
                       WorldService worldService,
                       HumanizerService humanizerService,
                       HeatmapService heatmapService) {
        this.mouseService = mouseService;
        this.cameraService = cameraService;
        this.worldService = worldService;
        this.humanizerService = humanizerService;
        this.heatmapService = heatmapService;
    }

public MouseService getMouseService() {return mouseService;}
public CameraService getCameraService() {return cameraService;}
public WorldService getWorldService() {return worldService;}
public HumanizerService getHumanizerService() {return humanizerService;}
public HeatmapService getHeatmapService() {return heatmapService;}
}
