package io.github.mattidragon.jsonpatcher.patch;

import io.github.mattidragon.jsonpatcher.JsonPatcher;
import io.github.mattidragon.jsonpatcher.config.Config;
import io.github.mattidragon.jsonpatcher.config.ConfigProvider;
import io.github.mattidragon.jsonpatcher.lang.parse.Lexer;
import io.github.mattidragon.jsonpatcher.lang.parse.Parser;
import io.github.mattidragon.jsonpatcher.misc.ValueOps;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public class PatchLoader {
    private static final FileToIdConverter finder = new FileToIdConverter("jsonpatch", ".jsonpatch");

    public static PatchStorage load(Executor executor, ResourceManager manager) {
        var files = finder.listMatchingResources(manager);
        var futures = new ArrayList<CompletableFuture<Void>>();
        var patches = Collections.synchronizedList(new ArrayList<Patch>());
        var errorCount = new AtomicInteger(0);
        for (var entry : files.entrySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                var patch = loadPatch(entry, errorCount);
                if (patch != null) {
                    patches.add(patch);
                }
            }, executor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        if (errorCount.get() > 0) {
            var message = "Failed to load %s patch(es). See logs/jsonpatch.log for details".formatted(errorCount.get());
            ErrorLogger.CURRENT.get().accept(Component.literal(message).withStyle(ChatFormatting.RED));
            JsonPatcher.MAIN_LOGGER.error(message);
            if (Config.MANAGER.get().throwOnFailure()) {
                throw new IllegalStateException(message);
            }
        }
        return new PatchStorage(patches);
    }

    @Nullable
    private static Patch loadPatch(Map.Entry<Identifier, Resource> entry, AtomicInteger errorCount) {
        var id = finder.fileToId(entry.getKey());
        var resource = entry.getValue();

        try {
            var code = new String(resource.open().readAllBytes(), StandardCharsets.UTF_8);
            var lexResult = Lexer.lex(ConfigProvider.INSTANCE, code, id.toString());

            var parseResult = Parser.parse(ConfigProvider.INSTANCE, lexResult.tokens());

            if (!parseResult.errors().isEmpty()) {
                logParseError(entry, parseResult.errors(), id);
                errorCount.incrementAndGet();
            } else {
                return validateAndBuild(id, parseResult);
            }
        } catch (IOException | Lexer.LexException | IllegalStateException e) {
            JsonPatcher.RELOAD_LOGGER.error("Failed to load patch {} from {}", id, entry.getKey(), e);
            errorCount.incrementAndGet();
        } catch (RuntimeException e) {
            JsonPatcher.RELOAD_LOGGER.error("Unexpected error while loading patches", e);
            errorCount.incrementAndGet();
        }
        return null;
    }

    private static void logParseError(Map.Entry<Identifier, Resource> entry, List<Parser.ParseException> errors, Identifier id) {
        if (Config.MANAGER.get().useJavaStacktrace()) {
            var error = new RuntimeException();
            errors.forEach(error::addSuppressed);
            JsonPatcher.RELOAD_LOGGER.error("Failed to parse patch {} from {}:", id, entry.getKey(), error);
        } else {
            JsonPatcher.RELOAD_LOGGER.error("Failed to parse patch {} from {}:\n{}", id, entry.getKey(), errors
                    .stream()
                    .map(Parser.ParseException::toString)
                    .collect(Collectors.joining("\n")));
        }
    }

    @Nullable
    private static Patch validateAndBuild(Identifier id, Parser.Result result) {
        var meta = result.metadata();
        if (meta.has("enabled") && !meta.getBoolean("enabled")) {
            return null;
        }

        if (!JsonPatcher.isSupportedVersion(meta.getString("version"))) {
            throw new IllegalStateException("Unsupported patch version '%s'".formatted(meta.getString("version")));
        }

        List<PatchTarget> target;
        if (meta.has("target")) {
            target = PatchTarget.LIST_CODEC.parse(ValueOps.INSTANCE, meta.get("target"))
                    .getOrThrow(error -> new IllegalStateException("Failed to parse target: %s".formatted(error)));
        } else {
            target = List.of();
        }

        double priority;
        if (meta.has("priority")) {
            priority = meta.getNumber("priority");
        } else {
            priority = 0;
        }

        return new Patch(result.program(), id, target, priority, meta.has("metapatch"));
    }
}
