package com.github.shikejing.reportenginejava.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 原子文件写入工具：先写临时文件，再通过原子 move 发布，
 * 保证并发读取永远不会看到半写状态。
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * 将内容原子写入目标文件。
     *
     * <p>临时文件与目标在同一目录下，{@link StandardCopyOption#ATOMIC_MOVE}
     * 保证发布动作对并发读取是原子的。
     */
    public static void writeAtomically(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = parent.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                // 某些文件系统不支持 ATOMIC_MOVE，退化为普通 move（仍优先保证不覆盖读者）。
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
