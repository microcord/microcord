import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public final class IsolateWebRtc {
    private static final String OUTLINE = "b/d/b/a/a";
    private static final String SYNTHETIC_PREFIX = "h0/c/";
    private static final String ISOLATED_PREFIX = "org/webrtc/internal/synthetic/";
    private static final Set<String> DESUGARED_COMPANIONS = Set.of(
            "h0/c/l0", "h0/c/m0", "h0/c/n0", "h0/c/o0", "h0/c/p0",
            "h0/c/q0", "h0/c/r0", "h0/c/s0", "h0/c/t0");
    private static final Set<String> FORBIDDEN = Set.of(
            "b/d/b/a/a", "com/discord/", "co/discord/", "com/hammerandchisel/");
    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "org/webrtc/RtpParameters$Encoding", Set.of(
                    "<init>(Ljava/lang/String;ZDILjava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Double;Ljava/lang/Long;)V"),
            "org/webrtc/voiceengine/WebRtcAudioTrack", Set.of("<init>(JZ)V"),
            "org/webrtc/PeerConnection$RTCConfiguration", Set.of(
                    "getEnableDtlsSrtp()Ljava/lang/Boolean;", "getEnableRtpDataChannel()Z"),
            "org/webrtc/EglHelper", Set.of("create()Lorg/webrtc/EglBase;"));

    private IsolateWebRtc() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: IsolateWebRtc <classes3.jar> <classes.jar> <output.jar>");
        }
        Map<String, byte[]> bridge = readClasses(Path.of(args[0]));
        Map<String, byte[]> primary = readClasses(Path.of(args[1]));
        TreeSet<String> selected = new TreeSet<>();
        bridge.keySet().stream().filter(name -> name.startsWith("org/webrtc/")).forEach(selected::add);
        require(selected.size() == 385, "expected 385 org.webrtc classes, found " + selected.size());
        selected.addAll(DESUGARED_COMPANIONS);

        ArrayDeque<String> pending = new ArrayDeque<>(selected);
        while (!pending.isEmpty()) {
            for (String reference : references(bridge.get(pending.remove()))) {
                if (reference.startsWith(SYNTHETIC_PREFIX) && selected.add(reference)) {
                    require(bridge.containsKey(reference), "missing synthetic class " + reference);
                    pending.add(reference);
                }
            }
        }
        long syntheticCount = selected.stream().filter(name -> name.startsWith(SYNTHETIC_PREFIX)).count();
        require(syntheticCount == 47, "expected 47 synthetic classes, found " + syntheticCount);

        TreeSet<String> outlineMethods = new TreeSet<>();
        for (String name : selected) collectOutlineMethods(bridge.get(name), outlineMethods);
        require(outlineMethods.size() == 12, "expected 12 outline methods, found " + outlineMethods.size());
        require(primary.containsKey(OUTLINE), "classes.dex does not contain " + OUTLINE);

        Map<String, String> renames = new HashMap<>();
        int index = 0;
        for (String name : selected) {
            if (name.startsWith(SYNTHETIC_PREFIX)) {
                renames.put(name, ISOLATED_PREFIX + String.format("Synthetic%02d", index++));
            }
        }
        renames.put(OUTLINE, "org/webrtc/internal/Outline");
        Remapper remapper = new Remapper() {
            @Override public String map(String internalName) {
                return renames.getOrDefault(internalName, internalName);
            }
        };

        TreeMap<String, byte[]> output = new TreeMap<>();
        for (String name : selected) {
            byte[] transformed = remap(bridge.get(name), remapper, null);
            output.put(new ClassReader(transformed).getClassName(), transformed);
        }
        byte[] outline = remap(primary.get(OUTLINE), remapper, outlineMethods);
        output.put(new ClassReader(outline).getClassName(), outline);

        verify(output);
        writeJar(Path.of(args[2]), output);
        System.out.printf("isolated %d WebRTC + %d synthetic classes; retained %d outline methods%n",
                385, syntheticCount, outlineMethods.size());
    }

    private static Map<String, byte[]> readClasses(Path path) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jar = new JarFile(path.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        byte[] bytes = input.readAllBytes();
                        classes.put(new ClassReader(bytes).getClassName(), bytes);
                    }
                }
            }
        }
        return classes;
    }

    private static Set<String> references(byte[] bytes) {
        ReferenceCollector collector = new ReferenceCollector();
        new ClassReader(bytes).accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return collector.references;
    }

    private static void collectOutlineMethods(byte[] bytes, Set<String> methods) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                            String desc, boolean isInterface) {
                        if (owner.equals(OUTLINE)) methods.add(method + desc);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static byte[] remap(byte[] bytes, Remapper remapper, Set<String> retainedMethods) {
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor target = new ClassRemapper(writer, remapper);
        if (retainedMethods != null) {
            target = new ClassVisitor(Opcodes.ASM9, target) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    return retainedMethods.contains(name + descriptor)
                            ? super.visitMethod(access, name, descriptor, signature, exceptions) : null;
                }
            };
        }
        new ClassReader(bytes).accept(target, 0);
        return writer.toByteArray();
    }

    private static void verify(Map<String, byte[]> classes) {
        require(classes.keySet().stream().filter(name -> name.startsWith("org/webrtc/")
                && !name.startsWith("org/webrtc/internal/")).count() == 385, "WebRTC class count changed");
        require(classes.keySet().stream().noneMatch(name -> name.startsWith(SYNTHETIC_PREFIX)),
                "h0.c class remained");
        int nativeBridgeClasses = 0;
        for (var entry : classes.entrySet()) {
            ReferenceCollector collector = new ReferenceCollector();
            Set<String> methods = new HashSet<>();
            boolean[] hasNative = {false};
            boolean[] calledByNative = {false};
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9, collector) {
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (descriptor.startsWith("Lorg/webrtc/CalledByNative")) calledByNative[0] = true;
                    return super.visitAnnotation(descriptor, visible);
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    methods.add(name + descriptor);
                    if ((access & Opcodes.ACC_NATIVE) != 0) hasNative[0] = true;
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (annotation.startsWith("Lorg/webrtc/CalledByNative")) calledByNative[0] = true;
                            return super.visitAnnotation(annotation, visible);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!entry.getKey().startsWith("org/webrtc/internal/") && (hasNative[0] || calledByNative[0]
                    || entry.getKey().equals("org/webrtc/CalledByNative")
                    || entry.getKey().equals("org/webrtc/CalledByNativeUnchecked")
                    || entry.getKey().equals("org/webrtc/EglHelper"))) nativeBridgeClasses++;
            for (String reference : collector.references) {
                require(!reference.startsWith(SYNTHETIC_PREFIX), "h0.c reference remained in " + entry.getKey());
                for (String forbidden : FORBIDDEN) {
                    require(!reference.startsWith(forbidden), "forbidden reference " + reference + " in " + entry.getKey());
                }
            }
            for (String required : REQUIRED.getOrDefault(entry.getKey(), Set.of())) {
                require(methods.contains(required), "missing descriptor " + entry.getKey() + "." + required);
            }
        }
        require(nativeBridgeClasses == 117,
                "expected 117 native bridge classes, found " + nativeBridgeClasses);
    }

    private static void writeJar(Path path, Map<String, byte[]> classes) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : classes.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey() + ".class");
                jarEntry.setTime(0);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class ReferenceCollector extends ClassVisitor {
        final Set<String> references = new HashSet<>();

        ReferenceCollector() { super(Opcodes.ASM9); }
        ReferenceCollector(ClassVisitor delegate) { super(Opcodes.ASM9, delegate); }

        private void type(String descriptor) {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.ARRAY) type(type.getElementType().getDescriptor());
            else if (type.getSort() == Type.OBJECT) references.add(type.getInternalName());
            else if (type.getSort() == Type.METHOD) {
                type(type.getReturnType().getDescriptor());
                for (Type argument : type.getArgumentTypes()) type(argument.getDescriptor());
            }
        }

        private void value(Object value) {
            if (value instanceof Type type) type(type.getDescriptor());
            else if (value instanceof Handle handle) {
                references.add(handle.getOwner());
                type(handle.getDesc());
            } else if (value instanceof ConstantDynamic dynamic) {
                type(dynamic.getDescriptor());
                value(dynamic.getBootstrapMethod());
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                    value(dynamic.getBootstrapMethodArgument(i));
                }
            }
        }

        @Override public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            if (superName != null) references.add(superName);
            if (interfaces != null) references.addAll(Arrays.asList(interfaces));
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override public void visitOuterClass(String owner, String name, String descriptor) {
            references.add(owner);
            if (descriptor != null) type(descriptor);
            super.visitOuterClass(owner, name, descriptor);
        }

        @Override public void visitInnerClass(String name, String outerName, String innerName, int access) {
            references.add(name);
            if (outerName != null) references.add(outerName);
            super.visitInnerClass(name, outerName, innerName, access);
        }

        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            type(descriptor);
            return super.visitAnnotation(descriptor, visible);
        }

        @Override public FieldVisitor visitField(int access, String name, String descriptor,
                String signature, Object value) {
            type(descriptor);
            value(value);
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            type(descriptor);
            if (exceptions != null) references.addAll(Arrays.asList(exceptions));
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override public void visitTypeInsn(int opcode, String type) {
                    references.add(type);
                    super.visitTypeInsn(opcode, type);
                }
                @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    references.add(owner); type(desc);
                    super.visitFieldInsn(opcode, owner, field, desc);
                }
                @Override public void visitMethodInsn(int opcode, String owner, String method,
                        String desc, boolean isInterface) {
                    references.add(owner); type(desc);
                    super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                }
                @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrap,
                        Object... arguments) {
                    type(desc); value(bootstrap);
                    for (Object argument : arguments) value(argument);
                    super.visitInvokeDynamicInsn(name, desc, bootstrap, arguments);
                }
                @Override public void visitLdcInsn(Object constant) {
                    value(constant);
                    super.visitLdcInsn(constant);
                }
                @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    type(descriptor);
                    super.visitMultiANewArrayInsn(descriptor, dimensions);
                }
                @Override public void visitTryCatchBlock(org.objectweb.asm.Label start,
                        org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                    if (type != null) references.add(type);
                    super.visitTryCatchBlock(start, end, handler, type);
                }
            };
        }
    }
}
