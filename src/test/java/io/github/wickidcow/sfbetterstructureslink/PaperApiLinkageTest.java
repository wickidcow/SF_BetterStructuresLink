package io.github.wickidcow.sfbetterstructureslink;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

class PaperApiLinkageTest {
    @Test void configurationIsCompiledAgainstTheRealPaperClass() throws Exception {
        assertFalse(FileConfiguration.class.isInterface(), "FileConfiguration must come from the real Paper API");
        boolean[] foundConfigCall = {false};
        try (var files = Files.walk(Path.of("target/classes"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                new ClassReader(Files.readAllBytes(file)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                                if (owner.equals("org/bukkit/configuration/file/FileConfiguration")) {
                                    foundConfigCall[0] = true;
                                    assertFalse(isInterface, file + " incorrectly treats FileConfiguration as an interface");
                                    assertEquals(Opcodes.INVOKEVIRTUAL, opcode);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(foundConfigCall[0], "No compiled config calls were inspected");
    }
}
