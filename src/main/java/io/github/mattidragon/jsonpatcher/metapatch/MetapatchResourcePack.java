package io.github.mattidragon.jsonpatcher.metapatch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

public class MetapatchResourcePack implements PackResources {
    public static final Gson GSON = new Gson();

    public final PackType type;
    private final Map<Identifier, JsonObject> files = new HashMap<>();
    private final List<FileFilter> filters = new ArrayList<>();
    private final Set<String> namespaces = new HashSet<>();

    public MetapatchResourcePack(PackType type) {
        this.type = type;
    }

    public void clear() {
        files.clear();
        filters.clear();
        namespaces.clear();
    }

    public void set(Map<Identifier, JsonObject> files, List<FileFilter> deletedFiles) {
        this.files.clear();
        this.files.putAll(files);
        this.filters.clear();
        this.filters.addAll(deletedFiles);
        namespaces.clear();
        files.keySet().forEach(id -> namespaces.add(id.getNamespace()));
    }

    public boolean isDeleted(Identifier id) {
        // The last filter added will get priority
        for (var filter : filters.reversed()) {
            if (filter.target().test(id)) {
                return !filter.allow();
            }
        }
        return false;
    }

    public Map<Identifier, Resource> findResources(String startingPath, Predicate<Identifier> allowedPathPredicate) {
        var map = new HashMap<Identifier, Resource>();
        files.forEach((id, file) -> {
            if (id.getPath().startsWith(startingPath) && allowedPathPredicate.test(id)) {
                map.put(id, makeResource(id));
            }
        });
        return map;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... segments) {
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (type != this.type) return null;
        var file = files.get(id);
        if (file == null) return null;

        return () -> {
            var out = new ByteArrayOutputStream();
            var writer = new OutputStreamWriter(out);
            GSON.toJson(file, writer);
            writer.close();
            return new ByteArrayInputStream(out.toByteArray());
        };
    }

    @Override
    public void listResources(PackType type, String namespace, String prefix, ResourceOutput consumer) {
        if (type != this.type) return;

        files.forEach((id, file) -> {
            if (id.getNamespace().equals(namespace) && id.getPath().startsWith(prefix)) {
                consumer.accept(id, getResource(type, id));
            }
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return namespaces;
    }

    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> metadataSerializer) throws IOException {
        var metadata = getMetadata(type);
        var stream = new ByteArrayInputStream(metadata.getBytes());
        return ResourceMetadata.fromJsonStream(stream).getSection(metadataSerializer).orElseThrow();
    }

    @Override
    public PackLocationInfo location() {
        return new PackLocationInfo("jsonpatcher:meta_patch", 
                Component.literal("JsonPatcher MetaPatch Resource Pack"), 
                PackSource.BUILT_IN, 
                Optional.empty());
    }

    @Override
    public void close() {

    }

    private static String getMetadata(PackType type) {
        return """
            {
              "pack": {
                "pack_format": %s,
                "description": "JsonPatcher MetaPatch Resource Pack"
              }
            }
            """.formatted(SharedConstants.getCurrentVersion().packVersion(type));
    }

    @Nullable
    public Resource makeResource(Identifier id) {
        var supplier = getResource(type, id);
        if (supplier != null) {
            return new Resource(this, supplier);
        }
        return null;
    }
}
