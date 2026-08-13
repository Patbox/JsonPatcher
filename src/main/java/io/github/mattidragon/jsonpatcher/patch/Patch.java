package io.github.mattidragon.jsonpatcher.patch;

import io.github.mattidragon.jsonpatcher.lang.runtime.Program;
import java.util.List;
import net.minecraft.resources.Identifier;

public record Patch(Program program, Identifier id, List<PatchTarget> target, double priority, boolean isMeta) {
}
