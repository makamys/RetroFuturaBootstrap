package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassFileUtils;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.rfbplugins.compat.ModernJavaCompatibilityPlugin;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Fixes a bug of ASM 5.0 used in the java 8 era of modding, leading to the following exception at runtime:
 * <pre>
 * cpw.mods.fml.common.LoaderException: java.lang.IncompatibleClassChangeError: Inconsistent constant pool data in
 * classfile for class com/gtnewhorizon/structurelib/alignment/IAlignmentLimits. Method 'boolean
 * lambda$static$0(...)' at index 77 is CONSTANT_MethodRef and should be CONSTANT_InterfaceMethodRef.
 * </pre>
 * <p>
 * See e.g. <a href="https://bugs.openjdk.org/browse/JDK-8027227">Bug JDK-8027227</a>,
 * <a href="https://bugs.openjdk.org/browse/JDK-8145148">Bug JDK-8145148</a>
 */
public class InterfaceMethodRefFixer implements RfbClassTransformer {
    /** Attribute to set to "true" on a JAR to skip class transforms from this transformer entirely */
    public static final Attributes.Name MANIFEST_SAFE_ATTRIBUTE = new Attributes.Name("Has-Safe-InterfaceMethodRefs");

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "interface-method-ref-fixer";
    }

    @Override
    public boolean shouldTransformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            byte @Nullable [] classBytes) {
        if (classBytes == null || classBytes.length < 16) {
            return false;
        }
        if (manifest != null && "true".equals(manifest.getMainAttributes().getValue(MANIFEST_SAFE_ATTRIBUTE))) {
            return false;
        }

        if (!ClassFileUtils.isValidClass(classBytes, 0)) {
            ModernJavaCompatibilityPlugin.log.warn(
                    "Invalid class {} found, skipping InterfaceMethodRef fixing", className);
            return false;
        }
        // Assume classes for java 9+ were not plagued by the asm 5.0 interfacemethodref bug.
        return ClassFileUtils.majorVersion(classBytes, 0) < Opcodes.V9;
    }

    @Override
    public void transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        final ClassNode node = classNode.getNode();
        if (node == null) {
            return;
        }
        final boolean iAmAnInterface = ((node.access & Opcodes.ACC_INTERFACE) != 0);
        final String internalClassName = node.name;
        if (node.methods == null) {
            return;
        }
        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                validateInstruction(classLoader, internalClassName, iAmAnInterface, insn);
            }
        }
    }

    private void validateInstruction(
            ExtensibleClassLoader classLoader,
            String internalClassName,
            boolean iAmAnInterface,
            AbstractInsnNode rawInsn) {
        // no-op
        if (rawInsn.getType() == AbstractInsnNode.INVOKE_DYNAMIC_INSN) {
            final InvokeDynamicInsnNode insn = (InvokeDynamicInsnNode) rawInsn;
            insn.bsm = fixHandle(classLoader, internalClassName, iAmAnInterface, insn.bsm);
            if (insn.bsmArgs != null) {
                for (int i = 0; i < insn.bsmArgs.length; i++) {
                    final Object arg = insn.bsmArgs[i];
                    if (arg instanceof Handle) {
                        insn.bsmArgs[i] = fixHandle(classLoader, internalClassName, iAmAnInterface, (Handle) arg);
                    }
                }
            }
        }
    }

    private Handle fixHandle(
            ExtensibleClassLoader classLoader, String internalClassName, boolean iAmAnInterface, Handle handle) {
        if (!handle.isInterface()) {
            final boolean fixSelfReference = handle.getOwner().equals(internalClassName) && iAmAnInterface;
            boolean fixJavaReference = false;
            if (!fixSelfReference && handle.getOwner().startsWith("java/")) {
                final String regularName = handle.getOwner().replace('/', '.');
                try {
                    final Class<?> javaClass = Class.forName(regularName, false, classLoader.asURLClassLoader());
                    if (javaClass.isInterface()) {
                        fixJavaReference = true;
                    }
                } catch (ClassNotFoundException cnfe) {
                    // no-op
                    ModernJavaCompatibilityPlugin.log.warn(
                            "Method handle to a non-existing class {} found.", regularName, cnfe);
                }
            }
            if (fixSelfReference || fixJavaReference) {
                ModernJavaCompatibilityPlugin.log.debug(
                        "Fixed a broken InterfaceMethodRef {} -> {}#{} ({})",
                        internalClassName,
                        handle.getOwner(),
                        handle.getName(),
                        handle.getDesc());
                return new Handle(handle.getTag(), handle.getOwner(), handle.getName(), handle.getDesc(), true);
            }
        }
        return handle;
    }
}
