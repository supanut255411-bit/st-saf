package dev.stonerestore.core;

/**
 * Immutable snapshot of the options that influence scanning. Being a record, equals/hashCode are
 * value based, which is what the scan cache uses to notice that settings changed.
 */
public record RestoreSettings(boolean blocks, boolean items, boolean entities, boolean gui,
                              boolean particles, boolean force, int minReplacements) {

    public boolean allows(TextureCategory category) {
        return switch (category) {
            case BLOCK -> blocks;
            case ITEM -> items;
            case ENTITY -> entities;
            case GUI -> gui;
            case PARTICLE -> particles;
            case OTHER -> true;
        };
    }
}
