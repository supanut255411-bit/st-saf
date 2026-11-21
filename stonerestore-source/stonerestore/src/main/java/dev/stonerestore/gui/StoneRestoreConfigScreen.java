package dev.stonerestore.gui;

import dev.stonerestore.StoneRestoreClient;
import dev.stonerestore.StoneRestoreEngine;
import dev.stonerestore.config.StoneRestoreConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Open with /stonerestore config. Resources are reloaded on close only if a relevant option changed. */
public final class StoneRestoreConfigScreen extends Screen {

    private static final int COLUMN_WIDTH = 150;
    private static final int GAP = 10;
    private static final int ROW_HEIGHT = 24;
    private static final int TOP = 46;

    private final Screen parent;
    private final StoneRestoreEngine engine;
    private final StoneRestoreConfig config;
    private final int fingerprintOnOpen;
    private boolean forceReload;

    public StoneRestoreConfigScreen(Screen parent) {
        super(Text.literal("StoneRestore"));
        this.parent = parent;
        this.engine = StoneRestoreClient.engine();
        this.config = engine.config();
        this.fingerprintOnOpen = config.fingerprint();
    }

    @Override
    protected void init() {
        int i = 0;
        toggle(i++, "Enable StoneRestore", () -> config.enabled, v -> config.enabled = v);
        toggle(i++, "Automatic Detection", () -> config.automaticDetection, v -> config.automaticDetection = v);
        toggle(i++, "Automatic Restoration", () -> config.automaticRestoration, v -> config.automaticRestoration = v);
        toggle(i++, "Restore Vanilla Blocks", () -> config.restoreBlocks, v -> config.restoreBlocks = v);
        toggle(i++, "Restore Vanilla Items", () -> config.restoreItems, v -> config.restoreItems = v);
        toggle(i++, "Restore Entity Textures", () -> config.restoreEntities, v -> config.restoreEntities = v);
        toggle(i++, "Restore GUI Textures", () -> config.restoreGui, v -> config.restoreGui = v);
        toggle(i++, "Restore Particle Textures", () -> config.restoreParticles, v -> config.restoreParticles = v);
        toggle(i++, "Preserve Other Resource Packs", () -> config.preserveOtherPacks, v -> config.preserveOtherPacks = v);
        toggle(i++, "Force Vanilla Textures", () -> config.forceVanilla, v -> config.forceVanilla = v);
        toggle(i++, "Debug Mode", () -> config.debug, v -> config.debug = v);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reload Restoration Data"), button -> {
            engine.clearCache();
            forceReload = true;
            close();
        }).dimensions(columnX(i), rowY(i), COLUMN_WIDTH, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(this.width / 2 - 100, TOP + 6 * ROW_HEIGHT + 22, 200, 20).build());
    }

    private int columnX(int index) {
        return index % 2 == 0 ? this.width / 2 - COLUMN_WIDTH - GAP / 2 : this.width / 2 + GAP / 2;
    }

    private int rowY(int index) {
        return TOP + (index / 2) * ROW_HEIGHT;
    }

    private void toggle(int index, String name, BooleanSupplier getter, Consumer<Boolean> setter) {
        ButtonWidget button = ButtonWidget.builder(label(name, getter.getAsBoolean()), b -> {
            setter.accept(!getter.getAsBoolean());
            b.setMessage(label(name, getter.getAsBoolean()));
        }).dimensions(columnX(index), rowY(index), COLUMN_WIDTH, 20).build();
        this.addDrawableChild(button);
    }

    private static Text label(String name, boolean on) {
        return Text.literal(name + ": ")
                .append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
        StoneRestoreEngine.Status status = engine.statusIndicator();
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status.text()), this.width / 2, 29, status.color());
    }

    @Override
    public void close() {
        config.save();
        if (forceReload || config.fingerprint() != fingerprintOnOpen) engine.requestReload();
        if (this.client != null) this.client.setScreen(parent);
    }
}
