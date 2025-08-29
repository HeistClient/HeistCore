package ht.heist.core;

import ht.heist.core.impl.HumanizerServiceImpl;
import ht.heist.core.impl.MouseServiceImpl;
import ht.heist.core.services.*;

public class CoreManager {
    private final MouseServiceImpl mouseService;
    private final CameraService cameraService;
    private final WorldService worldService;
    private final HumanizerServiceImpl humanizerService;
    private final HeatmapService heatmapService;

    public CoreManager(MouseServiceImpl mouseService,
                       CameraService cameraService,
                       WorldService worldService,
                       HumanizerServiceImpl humanizerService,
                       HeatmapService heatmapService) {
        this.mouseService = mouseService;
        this.cameraService = cameraService;
        this.worldService = worldService;
        this.humanizerService = humanizerService;
        this.heatmapService = heatmapService;
    }

public MouseServiceImpl getMouseService() {return mouseService;}
public CameraService getCameraService() {return cameraService;}
public WorldService getWorldService() {return worldService;}
public HumanizerServiceImpl getHumanizerService() {return humanizerService;}
public HeatmapService getHeatmapService() {return heatmapService;}
}
