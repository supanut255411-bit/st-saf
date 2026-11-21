package dev.stonerestore.pack;

import dev.stonerestore.core.ScanResult;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.metadata.ResourceMetadataSerializer;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * The "virtual restoration layer".
 *
 * <p>Wraps one user pack and hides the textures that were detected as stone copies. The pack stays
 * enabled and keeps its position, its other textures, models, lang files and pack.mcmeta; nothing on
 * disk is touched. Because the hiding happens <em>inside</em> the pack, pack order cannot defeat it:
 * a lookup for {@code minecraft:textures/block/dirt.png} simply no longer finds anything in this pack
 * and falls through to the next pack down (or to vanilla). With "preserve other packs" switched off
 * this pack serves the vanilla file itself instead, so it also beats lower packs.
 *
 * <p>The scan is lazy: it runs the first time anything asks this pack for a texture, which is on a
 * resource-loading worker thread, not the render thread.
 */
public final class RestoringResourcePack implements ResourcePack {

    private final ResourcePack delegate;
    private final ResourcePack vanilla;
    private final ReloadSession session;
    private volatile ScanResult result;

    RestoringResourcePack(ResourcePack delegate, ResourcePack vanilla, ReloadSession session) {
        this.delegate = delegate;
        this.vanilla = vanilla;
        this.session = session;
    }

    public ResourcePack delegate() {
        return delegate;
    }

    public String packId() {
        return delegate.getId();
    }

    /** Scan result for this pack, computing it (once) if needed. */
    public ScanResult plan() {
        ScanResult r = result;
        if (r != null) return r;
        synchronized (this) {
            if (result == null) result = session.scanPack(this);
            return result;
        }
    }

    public boolean applies() {
        return session.applyRestoration() && plan().flagged();
    }

    private boolean intercepts(ResourceType type, Identifier id) {
        if (type != ResourceType.CLIENT_RESOURCES) return false;
        if (!"minecraft".equals(id.getNamespace())) return false;
        String path = id.getPath();
        if (!path.startsWith("textures/")) return false; // don't trigger a scan for models, lang, sounds...
        return applies() && plan().isHidden(path);
    }

    @Nullable
    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        if (intercepts(type, id)) {
            return session.preserveOthers() ? null : vanilla.open(type, id);
        }
        return delegate.open(type, id);
    }

    @Override
    public void findResources(ResourceType type, String namespace, String prefix, ResourcePack.ResultConsumer consumer) {
        boolean touchesTextures = "textures".startsWith(prefix) || prefix.startsWith("textures");
        if (type != ResourceType.CLIENT_RESOURCES || !"minecraft".equals(namespace) || !touchesTextures || !applies()) {
            delegate.findResources(type, namespace, prefix, consumer);
            return;
        }
        ScanResult r = plan();
        boolean preserve = session.preserveOthers();
        delegate.findResources(type, namespace, prefix, (id, supplier) -> {
            if (!r.isHidden(id.getPath())) {
                consumer.accept(id, supplier);
            } else if (!preserve) {
                InputSupplier<InputStream> original = vanilla.open(type, id);
                if (original != null) consumer.accept(id, original);
            }
        });
    }

    @Nullable
    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        return delegate.openRoot(segments);
    }

    @Override
    public Set<String> getNamespaces(ResourceType type) {
        return delegate.getNamespaces(type);
    }

    @Nullable
    @Override
    public <T> T parseMetadata(ResourceMetadataSerializer<T> metadataSerializer) throws IOException {
        return delegate.parseMetadata(metadataSerializer);
    }

    @Override
    public ResourcePackInfo getInfo() {
        return delegate.getInfo();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
