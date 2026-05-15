package com.rinha.dataset;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

/**
 * Wrapper FFM pra {@code madvise(2)} do Linux. Permite anunciar pro kernel
 * que uma região mmap'd deve ser pre-faulted (WILLNEED) ou promovida a
 * páginas grandes (HUGEPAGE).
 *
 * Falha silenciosamente em sistemas não-Linux, dentro de native-image sem
 * a função exportada, ou se o buffer não for direct/mmap'd. Sempre seguro
 * chamar — quem chama só decide se quer o hint.
 */
public final class NativeMemAdvise {

    // Códigos de advice no Linux (asm-generic/mman-common.h).
    private static final int MADV_WILLNEED = 3;
    private static final int MADV_HUGEPAGE = 14;

    private static final MethodHandle MADVISE;
    private static final boolean AVAILABLE;

    static {
        MethodHandle mh = null;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            mh = libc.find("madvise")
                    .map(addr -> linker.downcallHandle(addr, FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT)))
                    .orElse(null);
        } catch (Throwable ignored) {
            mh = null;
        }
        MADVISE = mh;
        AVAILABLE = mh != null;
    }

    private NativeMemAdvise() {}

    public static boolean available() {
        return AVAILABLE;
    }

    /** Toca cada página da região (kernel paga as pages no page cache). */
    public static boolean willNeed(ByteBuffer buf) {
        return advise(buf, MADV_WILLNEED);
    }

    /** Pede pro kernel promover a região a transparent huge pages. */
    public static boolean hugePage(ByteBuffer buf) {
        return advise(buf, MADV_HUGEPAGE);
    }

    private static boolean advise(ByteBuffer buf, int advice) {
        if (!AVAILABLE || buf == null || !buf.isDirect()) return false;
        try {
            MemorySegment seg = MemorySegment.ofBuffer(buf);
            int rc = (int) MADVISE.invoke(seg, (long) buf.capacity(), advice);
            return rc == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
