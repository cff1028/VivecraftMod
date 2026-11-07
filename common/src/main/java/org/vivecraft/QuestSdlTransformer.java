package org.vivecraft;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class QuestSdlTransformer {
    private static final Set<String> OWNERS = Set.of(
        "org/lwjgl/sdl/SDLVideo", "org/lwjgl/sdl/SDLMouse", "org/lwjgl/sdl/SDLKeyboard",
        "org/lwjgl/sdl/SDLEvents", "org/lwjgl/sdl/SDLClipboard", "org/lwjgl/sdl/SDLInit");

    private final Map<String, String> methods = new HashMap<>();

    public QuestSdlTransformer(ClassNode... bridges) {
        for (ClassNode bridge : bridges) {
            for (var method : bridge.methods) {
                if (method.name.startsWith("SDL_") &&
                    (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) ==
                        (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                {
                    String previous = this.methods.put(method.name + method.desc, bridge.name);
                    if (previous != null) throw new IllegalArgumentException("Duplicate SDL bridge: " + method.name);
                }
            }
        }
    }

    public void transform(ClassNode target) {
        for (var method : target.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                    call.owner = redirect(call.owner, call.name, call.desc);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    dynamic.bsm = (Handle) redirectConstant(dynamic.bsm);
                    for (int i = 0; i < dynamic.bsmArgs.length; i++) {
                        dynamic.bsmArgs[i] = redirectConstant(dynamic.bsmArgs[i]);
                    }
                } else if (instruction instanceof LdcInsnNode constant) {
                    constant.cst = redirectConstant(constant.cst);
                }
            }
        }
    }

    private String redirect(String owner, String name, String descriptor) {
        if (!OWNERS.contains(owner) || name.equals("SDL_SetAppMetadataProperty")) return owner;
        String bridge = this.methods.get(name + descriptor);
        if (bridge == null) throw new IllegalStateException("Missing Quest SDL bridge: " + owner + "." + name + descriptor);
        return bridge;
    }

    private Object redirectConstant(Object value) {
        if (value instanceof Handle handle && handle.getTag() == Opcodes.H_INVOKESTATIC) {
            return new Handle(handle.getTag(), redirect(handle.getOwner(), handle.getName(), handle.getDesc()),
                handle.getName(), handle.getDesc(), handle.isInterface());
        }
        if (value instanceof ConstantDynamic dynamic) {
            Object[] args = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < args.length; i++) args[i] = redirectConstant(dynamic.getBootstrapMethodArgument(i));
            return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(),
                (Handle) redirectConstant(dynamic.getBootstrapMethod()), args);
        }
        return value;
    }
}
