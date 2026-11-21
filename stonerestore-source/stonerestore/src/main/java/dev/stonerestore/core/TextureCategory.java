package dev.stonerestore.core;

/** Which user-facing toggle a texture path belongs to. */
public enum TextureCategory {
    BLOCK, ITEM, ENTITY, GUI, PARTICLE, OTHER;

    public static TextureCategory of(String path) {
        if (path.startsWith("textures/block/") || path.startsWith("textures/colormap/")) return BLOCK;
        if (path.startsWith("textures/item/")) return ITEM;
        if (path.startsWith("textures/entity/")) return ENTITY;
        if (path.startsWith("textures/gui/")) return GUI;
        if (path.startsWith("textures/particle/")) return PARTICLE;
        return OTHER;
    }
}
