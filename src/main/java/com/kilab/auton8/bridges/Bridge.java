package com.kilab.auton8.bridges;

public interface Bridge {
    void enable();
    void disable();

    default void onCommand(String json) {} // optional
}
