package ht.heist.core.impl;

import ht.heist.core.services.MouseService;

public class DefaultMouseService implements MouseService {

    @Override
    public void move(int x, int y) {
        // TODO: Hook into RuneLite's mouse movement
        System.out.println("Moving mouse to: " + x + ", " + y);
    }

    @Override
    public void click(int x, int y) {
        // TODO: Hook into RuneLite's mouse click
        System.out.println("Clicking mouse at: " + x + ", " + y);
    }
}
