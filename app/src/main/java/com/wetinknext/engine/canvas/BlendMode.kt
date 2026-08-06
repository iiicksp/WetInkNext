package com.wetinknext.engine.canvas

/** Stored per layer now; shader implementations beyond NORMAL are scheduled after P6. */
enum class BlendMode(val id: Int) {
    NORMAL(0),
    MULTIPLY(1),
    SCREEN(2),
    OVERLAY(3),
    DARKEN(4),
    LIGHTEN(5),
    ADD(6),
}
